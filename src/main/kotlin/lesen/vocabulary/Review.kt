package lesen.vocabulary

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Access
import lesen.common.Learner
import lesen.common.requireLength
import lesen.dictionary.DictionaryService
import lesen.dictionary.DictionaryStore
import lesen.reading.ReadingCompletionListener
import lesen.reading.ReadingVersions
import lesen.reading.TokenKind
import lesen.reading.VersionRef
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class WordState(
    val key: String,
    val status: VocabularyStatus,
)

// lo que la persona ya conoce de un idioma, para resaltar en el texto: lo guardado (con su estado) y lo encontrado
data class KnownWords(
    val saved: List<WordState>,
    val encountered: List<String>,
)

data class ReviewCard(
    val item: VocabularyItem,
    val state: CardState,
    // la próxima fecha que daría cada respuesta, para enseñarla en los botones
    val preview: Map<Rating, Instant>,
)

data class ReviewQueue(
    val cards: List<ReviewCard>,
    val due: Int,
    // la próxima palabra que tocará repasar cuando ahora no toca ninguna
    val nextDueAt: Instant?,
)

data class ReviewRequest(
    val vocabularyId: UUID,
    val rating: Rating,
    // lo pone el cliente: repetir la misma petición no repasa dos veces
    val clientId: String,
)

data class ReviewOutcome(
    val item: VocabularyItem,
    val state: CardState,
    val previousStatus: VocabularyStatus,
)

data class VocabularyStats(
    val total: Int,
    val byStatus: Map<VocabularyStatus, Int>,
    val dueNow: Int,
    val reviewedToday: Int,
    val encountered: Int,
)

@Repository
class ReviewStore(
    private val db: DatabaseClient,
    private val words: VocabularyStore,
) {
    // cada entrada del diccionario de la versión, con su primera aparición; repetir la lectura no duplica nada
    suspend fun encounter(
        o: UUID,
        u: UUID,
        version: VersionRef,
        firstTokens: Map<String, String>,
    ) {
        if (firstTokens.isEmpty()) return
        db
            .sql(
                """
                INSERT INTO lesen.vocabulary_encounter (organization_id, user_id, entry_key, language, reading_id, version_number, token_ref)
                SELECT :o, :u, k, :l, :r, :v, t FROM unnest(CAST(:keys AS text[]), CAST(:tokens AS text[])) AS x(k, t)
                WHERE EXISTS (SELECT 1 FROM lesen.dictionary_entry e WHERE e.key = k)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("l", version.language)
            .bind("r", version.readingId)
            .bind("v", version.number)
            .bind("keys", firstTokens.keys.toTypedArray())
            .bind("tokens", firstTokens.values.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun encountered(
        o: UUID,
        u: UUID,
        language: String,
    ): List<String> =
        db
            .sql(
                "SELECT DISTINCT entry_key FROM lesen.vocabulary_encounter WHERE organization_id = :o AND user_id = :u AND language = :l ORDER BY entry_key",
            ).bind("o", o)
            .bind("u", u)
            .bind("l", language)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun states(
        o: UUID,
        u: UUID,
        language: String,
    ): List<WordState> =
        db
            .sql("SELECT entry_key, status FROM lesen.vocabulary_entry WHERE organization_id = :o AND user_id = :u AND language = :l ORDER BY entry_key")
            .bind("o", o)
            .bind("u", u)
            .bind("l", language)
            .map { row, _ -> WordState(row.get(0, String::class.java)!!, VocabularyStatus.valueOf(row.get(1, String::class.java)!!)) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun due(
        o: UUID,
        u: UUID,
        language: String?,
        reading: String?,
        at: Instant,
        limit: Int,
    ): List<VocabularyStore.Row> =
        filtered(
            db.sql("${VocabularyStore.SELECT} WHERE ${where(language, reading)} AND x.due_at <= :at ORDER BY x.due_at, x.id LIMIT :limit"),
            o,
            u,
            language,
            reading,
        ).bind("at", at)
            .bind("limit", limit)
            .map { row, _ -> words.row(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun dueCount(
        o: UUID,
        u: UUID,
        language: String?,
        reading: String?,
        at: Instant,
    ): Pair<Int, Instant?> =
        filtered(
            db.sql(
                "SELECT count(*) FILTER (WHERE x.due_at <= :at) AS due, min(x.due_at) FILTER (WHERE x.due_at > :at) AS next " +
                    "FROM lesen.vocabulary_entry x LEFT JOIN lesen.reading r ON r.id = x.reading_id WHERE ${where(language, reading)}",
            ),
            o,
            u,
            language,
            reading,
        ).bind("at", at)
            .map { row, _ -> row.get("due", Number::class.java)!!.toInt() to row.get("next", Instant::class.java) }
            .one()
            .awaitSingle()

    suspend fun forUpdate(
        o: UUID,
        u: UUID,
        id: UUID,
    ): VocabularyStore.Row? =
        db
            .sql("${VocabularyStore.SELECT} WHERE x.organization_id = :o AND x.user_id = :u AND x.id = :id FOR UPDATE OF x")
            .bind("o", o)
            .bind("u", u)
            .bind("id", id)
            .map { row, _ -> words.row(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun reviewed(
        o: UUID,
        u: UUID,
        clientId: String,
    ): UUID? =
        db
            .sql("SELECT vocabulary_id FROM lesen.vocabulary_review WHERE organization_id = :o AND user_id = :u AND client_id = :c")
            .bind("o", o)
            .bind("u", u)
            .bind("c", clientId)
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .one()
            .awaitSingleOrNull()

    suspend fun apply(
        o: UUID,
        u: UUID,
        id: UUID,
        card: Card,
        status: VocabularyStatus,
        lapse: Boolean,
    ) {
        db
            .sql(
                """
                UPDATE lesen.vocabulary_entry SET card_state = :state, card_step = :step, stability = :s, difficulty = :d, due_at = :due,
                  last_review_at = :last, status = :status, reviews = reviews + 1, lapses = lapses + :lapse, updated_at = now()
                WHERE id = :id AND organization_id = :o AND user_id = :u
                """.trimIndent(),
            ).bind("state", card.state.name)
            .let { if (card.step == null) it.bindNull("step", Integer::class.java) else it.bind("step", card.step) }
            .bind("s", card.stability!!)
            .bind("d", card.difficulty!!)
            .bind("due", card.due)
            .bind("last", card.lastReview!!)
            .bind("status", status.name)
            .bind("lapse", if (lapse) 1 else 0)
            .bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun log(
        o: UUID,
        u: UUID,
        id: UUID,
        request: ReviewRequest,
        elapsedDays: Long?,
        card: Card,
        status: VocabularyStatus,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.vocabulary_review
                  (organization_id, user_id, vocabulary_id, client_id, rating, reviewed_at, elapsed_days, stability, difficulty, due_at, status_after)
                VALUES (:o, :u, :id, :c, :rating, :at, :elapsed, :s, :d, :due, :status)
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("id", id)
            .bind("c", request.clientId)
            .bind("rating", request.rating.grade)
            .bind("at", card.lastReview!!)
            .let { if (elapsedDays == null) it.bindNull("elapsed", Integer::class.java) else it.bind("elapsed", elapsedDays.toInt()) }
            .bind("s", card.stability!!)
            .bind("d", card.difficulty!!)
            .bind("due", card.due)
            .bind("status", status.name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun stats(
        o: UUID,
        u: UUID,
        language: String?,
        at: Instant,
        dayStart: Instant,
    ): VocabularyStats {
        val byStatus =
            db
                .sql(
                    "SELECT status, count(*) AS n FROM lesen.vocabulary_entry WHERE organization_id = :o AND user_id = :u " +
                        "AND (CAST(:l AS text) IS NULL OR language = :l) GROUP BY status",
                ).bind("o", o)
                .bind("u", u)
                .let { if (language == null) it.bindNull("l", String::class.java) else it.bind("l", language) }
                .map { row, _ -> VocabularyStatus.valueOf(row.get("status", String::class.java)!!) to row.get("n", Number::class.java)!!.toInt() }
                .all()
                .collectList()
                .awaitSingle()
                .toMap()
        val counts =
            db
                .sql(
                    """
                    SELECT
                      (SELECT count(*) FROM lesen.vocabulary_entry WHERE organization_id = :o AND user_id = :u
                         AND (CAST(:l AS text) IS NULL OR language = :l) AND due_at <= :at) AS due,
                      (SELECT count(*) FROM lesen.vocabulary_review rv JOIN lesen.vocabulary_entry x ON x.id = rv.vocabulary_id
                         WHERE rv.organization_id = :o AND rv.user_id = :u AND (CAST(:l AS text) IS NULL OR x.language = :l)
                         AND rv.reviewed_at >= :day) AS reviewed,
                      (SELECT count(DISTINCT entry_key) FROM lesen.vocabulary_encounter WHERE organization_id = :o AND user_id = :u
                         AND (CAST(:l AS text) IS NULL OR language = :l)) AS encountered
                    """.trimIndent(),
                ).bind("o", o)
                .bind("u", u)
                .let { if (language == null) it.bindNull("l", String::class.java) else it.bind("l", language) }
                .bind("at", at)
                .bind("day", dayStart)
                .map {
                    row,
                    _,
                    ->
                    Triple(
                        row.get("due", Number::class.java)!!.toInt(),
                        row.get("reviewed", Number::class.java)!!.toInt(),
                        row.get("encountered", Number::class.java)!!.toInt(),
                    )
                }.one()
                .awaitSingle()
        val all = VocabularyStatus.entries.associateWith { byStatus[it] ?: 0 }
        return VocabularyStats(all.values.sum(), all, counts.first, counts.second, counts.third)
    }

    private fun where(
        language: String?,
        reading: String?,
    ) = buildString {
        append("x.organization_id = :o AND x.user_id = :u")
        if (language != null) append(" AND x.language = :l")
        if (reading != null) append(" AND r.slug = :reading")
    }

    private fun filtered(
        spec: DatabaseClient.GenericExecuteSpec,
        o: UUID,
        u: UUID,
        language: String?,
        reading: String?,
    ): DatabaseClient.GenericExecuteSpec {
        var s = spec.bind("o", o).bind("u", u)
        if (language != null) s = s.bind("l", language)
        if (reading != null) s = s.bind("reading", reading)
        return s
    }
}

// anota los encuentros al terminar una lectura: cada entrada del diccionario de la versión, una vez
@Component
class EncounterRecorder(
    private val store: ReviewStore,
    private val versions: ReadingVersions,
) : ReadingCompletionListener {
    override suspend fun completed(
        organizationId: UUID,
        userId: UUID,
        version: VersionRef,
    ) {
        val first = linkedMapOf<String, String>()
        for (token in versions.content(version.versionId).words) {
            if (token.kind == TokenKind.WORD && token.entry != null) first.putIfAbsent(token.entry, token.id)
        }
        store.encounter(organizationId, userId, version, first)
    }
}

@Service
class ReviewService(
    private val store: ReviewStore,
    private val vocabulary: VocabularyService,
    private val dictionary: DictionaryService,
    private val entries: DictionaryStore,
    private val tx: TransactionalOperator,
    private val clock: Clock,
) {
    suspend fun known(
        me: Learner,
        language: String,
    ): KnownWords {
        requireLength(language, "language", 8)
        return KnownWords(store.states(me.organizationId, me.userId, language), store.encountered(me.organizationId, me.userId, language))
    }

    suspend fun next(
        me: Learner,
        language: String?,
        reading: String?,
        limit: Int?,
    ): ReviewQueue {
        requireLength(language, "language", 8)
        requireLength(reading, "reading", 120)
        val size = limit ?: 20
        if (size !in 1..50) throw ValidationException("Invalid limit", "limit", "must be between 1 and 50")
        val now = clock.instant()
        val rows = store.due(me.organizationId, me.userId, language, reading, now, size)
        val into = dictionary.translationLanguage(me, null)
        val stored = entries.entries(rows.map { it.entryKey }.distinct()).associateBy { it.key }
        val (due, next) = store.dueCount(me.organizationId, me.userId, language, reading, now)
        return ReviewQueue(
            rows.map { row ->
                ReviewCard(
                    vocabulary.item(row, stored.getValue(row.entryKey), into),
                    row.card.state,
                    Rating.entries.associateWith { Fsrs.review(row.card, it, now).due },
                )
            },
            due,
            next,
        )
    }

    suspend fun review(
        me: Learner,
        request: ReviewRequest,
    ): ReviewOutcome {
        if (request.clientId.isBlank()) throw ValidationException("Missing clientId", "clientId", "required")
        requireLength(request.clientId, "clientId", 64)
        val now = clock.instant()
        val (row, previous) =
            tx.executeAndAwait {
                val row = store.forUpdate(me.organizationId, me.userId, request.vocabularyId) ?: throw NotFoundException("Word not found")
                // el mismo repaso repetido (un reintento del cliente) devuelve el estado sin volver a aplicarlo
                if (store.reviewed(me.organizationId, me.userId, request.clientId) != null) return@executeAndAwait row to row.status
                val card = Fsrs.review(row.card, request.rating, now)
                val status = ReviewStatus.of(card)
                val elapsed = row.card.lastReview?.let { Math.floorDiv(Duration.between(it, now).seconds, 86_400L) }
                store.apply(me.organizationId, me.userId, row.id, card, status, request.rating == Rating.AGAIN && row.card.state == CardState.REVIEW)
                store.log(me.organizationId, me.userId, row.id, request, elapsed, card, status)
                store.forUpdate(me.organizationId, me.userId, row.id)!! to row.status
            }
        val into = dictionary.translationLanguage(me, null)
        return ReviewOutcome(vocabulary.item(row, entries.entry(row.entryKey)!!, into), row.card.state, previous)
    }

    suspend fun stats(
        me: Learner,
        language: String?,
    ): VocabularyStats {
        requireLength(language, "language", 8)
        val now = clock.instant()
        // «hoy» en UTC: el día local de cada persona llega con las metas de la fase 5
        return store.stats(
            me.organizationId,
            me.userId,
            language,
            now,
            now
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC),
        )
    }
}

@RestController
@RequestMapping("/api/lesen/vocabulary")
class ReviewController(
    private val access: Access,
    private val service: ReviewService,
) {
    // lo guardado y lo encontrado de un idioma, para resaltar palabras nuevas o aprendidas en el texto
    @GetMapping("/known")
    suspend fun known(
        @RequestParam language: String,
    ): KnownWords = service.known(access.notebook(), language)

    @GetMapping("/reviews/next")
    suspend fun next(
        @RequestParam language: String?,
        @RequestParam reading: String?,
        @RequestParam limit: Int?,
    ): ReviewQueue = service.next(access.notebook(), language, reading, limit)

    @PostMapping("/reviews")
    suspend fun review(
        @RequestBody request: ReviewRequest,
    ): ReviewOutcome = service.review(access.writeNotebook(), request)

    @GetMapping("/stats")
    suspend fun stats(
        @RequestParam language: String?,
    ): VocabularyStats = service.stats(access.notebook(), language)
}
