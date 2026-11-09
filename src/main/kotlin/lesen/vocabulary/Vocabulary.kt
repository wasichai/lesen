package lesen.vocabulary

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Access
import lesen.common.ActivityType
import lesen.common.Learner
import lesen.common.LearningActivity
import lesen.common.LearningActivityListener
import lesen.common.Page
import lesen.common.PageRequest
import lesen.common.emit
import lesen.common.requireLength
import lesen.dictionary.DictionaryService
import lesen.dictionary.DictionaryStore
import lesen.reading.ReadingVersions
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

// estados del cuaderno (docs/architecture/domain-model.md §Vocabulario). Guardada no es lo mismo que recordada: SAVED
// hasta el primer repaso, y los demás estados los da el repaso espaciado (ReviewStatus, ADR-0010), nunca un botón
enum class VocabularyStatus { SAVED, LEARNING, FAMILIAR, MASTERED }

data class SaveWordRequest(
    val entryKey: String,
    val reading: String? = null,
    val version: Int? = null,
    val tokenRef: String? = null,
)

data class NoteChange(
    val note: String?,
)

data class VocabularySource(
    val slug: String,
    val title: String,
    val version: Int,
)

data class VocabularyItem(
    val id: UUID,
    val entryKey: String,
    val lemma: String,
    val pos: String,
    val language: String,
    val translations: List<String>,
    val surface: String,
    val context: String?,
    val tokenRef: String?,
    val source: VocabularySource?,
    val status: VocabularyStatus,
    val note: String?,
    val savedAt: Instant,
    // la próxima fecha de repaso; una palabra recién guardada se puede repasar ya
    val dueAt: Instant,
    val reviews: Int,
)

data class SavedWord(
    val item: VocabularyItem,
    val created: Boolean,
)

@Repository
class VocabularyStore(
    private val db: DatabaseClient,
) {
    suspend fun insert(
        o: UUID,
        u: UUID,
        key: String,
        language: String,
        into: String,
        surface: String,
        context: String?,
        reading: UUID?,
        version: Int?,
        token: String?,
    ): Boolean =
        db
            .sql(
                """
                INSERT INTO lesen.vocabulary_entry
                  (organization_id, user_id, entry_key, language, translation_language, surface, context, reading_id, version_number, token_ref, status)
                VALUES (:o, :u, :k, :l, :t, :s, :c, :r, :v, :tok, 'SAVED')
                ON CONFLICT (organization_id, user_id, entry_key) DO NOTHING
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("k", key)
            .bind("l", language)
            .bind("t", into)
            .bind("s", surface)
            .let { if (context == null) it.bindNull("c", String::class.java) else it.bind("c", context) }
            .let { if (reading == null) it.bindNull("r", UUID::class.java) else it.bind("r", reading) }
            .let { if (version == null) it.bindNull("v", Integer::class.java) else it.bind("v", version) }
            .let { if (token == null) it.bindNull("tok", String::class.java) else it.bind("tok", token) }
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun list(
        o: UUID,
        u: UUID,
        reading: String?,
        status: VocabularyStatus?,
        text: String?,
        page: PageRequest,
    ): Pair<List<Row>, Long> {
        val where =
            buildString {
                append("x.organization_id = :o AND x.user_id = :u")
                if (reading != null) append(" AND r.slug = :reading")
                if (status != null) append(" AND x.status = :status")
                if (!text.isNullOrBlank()) append(" AND (e.search LIKE :text OR lower(x.surface) LIKE :text)")
            }

        fun bindAll(spec: DatabaseClient.GenericExecuteSpec): DatabaseClient.GenericExecuteSpec {
            var s = spec.bind("o", o).bind("u", u)
            if (reading != null) s = s.bind("reading", reading)
            if (status != null) s = s.bind("status", status.name)
            if (!text.isNullOrBlank()) {
                s =
                    s.bind(
                        "text",
                        text
                            .trim()
                            .lowercase()
                            .replace("%", "\\%")
                            .replace("_", "\\_") + "%",
                    )
            }
            return s
        }
        val rows =
            bindAll(db.sql("$SELECT WHERE $where ORDER BY x.saved_at DESC, x.id LIMIT :limit OFFSET :offset"))
                .bind("limit", page.size)
                .bind("offset", page.offset)
                .map { row, _ -> row(row) }
                .all()
                .collectList()
                .awaitSingle()
        val total =
            bindAll(db.sql("SELECT count(*) FROM ($SELECT WHERE $where) c"))
                .map { row, _ -> row.get(0, Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        return rows to total
    }

    suspend fun keys(
        o: UUID,
        u: UUID,
        language: String?,
    ): List<String> =
        db
            .sql(
                "SELECT entry_key FROM lesen.vocabulary_entry WHERE organization_id = :o AND user_id = :u " +
                    "AND (CAST(:l AS text) IS NULL OR language = :l) ORDER BY entry_key",
            ).bind("o", o)
            .bind("u", u)
            .let { if (language == null) it.bindNull("l", String::class.java) else it.bind("l", language) }
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun byKey(
        o: UUID,
        u: UUID,
        key: String,
    ): Row? =
        db
            .sql("$SELECT WHERE x.organization_id = :o AND x.user_id = :u AND x.entry_key = :k")
            .bind("o", o)
            .bind("u", u)
            .bind("k", key)
            .map { row, _ -> row(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun byId(
        o: UUID,
        u: UUID,
        id: UUID,
    ): Row? =
        db
            .sql("$SELECT WHERE x.organization_id = :o AND x.user_id = :u AND x.id = :id")
            .bind("o", o)
            .bind("u", u)
            .bind("id", id)
            .map { row, _ -> row(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun note(
        o: UUID,
        u: UUID,
        id: UUID,
        note: String?,
    ): Boolean =
        db
            .sql("UPDATE lesen.vocabulary_entry SET note = :n, updated_at = now() WHERE id = :id AND organization_id = :o AND user_id = :u")
            .let { if (note.isNullOrBlank()) it.bindNull("n", String::class.java) else it.bind("n", note.trim()) }
            .bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun delete(
        o: UUID,
        u: UUID,
        id: UUID,
    ): Boolean =
        db
            .sql("DELETE FROM lesen.vocabulary_entry WHERE id = :id AND organization_id = :o AND user_id = :u")
            .bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    data class Row(
        val id: UUID,
        val entryKey: String,
        val language: String,
        val surface: String,
        val context: String?,
        val tokenRef: String?,
        val slug: String?,
        val title: String?,
        val version: Int?,
        val status: VocabularyStatus,
        val note: String?,
        val savedAt: Instant,
        val card: Card,
        val reviews: Int,
        val lapses: Int,
    )

    fun row(r: Readable) =
        Row(
            r.get("id", UUID::class.java)!!,
            r.get("entry_key", String::class.java)!!,
            r.get("language", String::class.java)!!,
            r.get("surface", String::class.java)!!,
            r.get("context", String::class.java),
            r.get("token_ref", String::class.java),
            r.get("slug", String::class.java),
            r.get("title", String::class.java),
            r.get("version_number", Number::class.java)?.toInt(),
            VocabularyStatus.valueOf(r.get("status", String::class.java)!!),
            r.get("note", String::class.java),
            r.get("saved_at", Instant::class.java)!!,
            Card(
                CardState.valueOf(r.get("card_state", String::class.java)!!),
                r.get("card_step", Number::class.java)?.toInt(),
                r.get("stability", java.lang.Double::class.java)?.toDouble(),
                r.get("difficulty", java.lang.Double::class.java)?.toDouble(),
                r.get("due_at", Instant::class.java)!!,
                r.get("last_review_at", Instant::class.java),
            ),
            r.get("reviews", Number::class.java)!!.toInt(),
            r.get("lapses", Number::class.java)!!.toInt(),
        )

    companion object {
        val SELECT =
            """
            SELECT x.id, x.entry_key, x.language, x.surface, x.context, x.token_ref, x.version_number, x.status, x.note, x.saved_at,
                   x.card_state, x.card_step, x.stability, x.difficulty, x.due_at, x.last_review_at, x.reviews, x.lapses,
                   r.slug, v.title
            FROM lesen.vocabulary_entry x
            JOIN lesen.dictionary_entry e ON e.key = x.entry_key
            LEFT JOIN lesen.reading r ON r.id = x.reading_id
            LEFT JOIN lesen.reading_version v ON v.reading_id = x.reading_id AND v.number = x.version_number
            """.trimIndent()
    }
}

@Service
class VocabularyService(
    private val store: VocabularyStore,
    private val dictionary: DictionaryService,
    private val entries: DictionaryStore,
    private val versions: ReadingVersions,
    private val activity: List<LearningActivityListener>,
) {
    // idempotente por (persona, entrada): guardar dos veces la misma palabra devuelve la que ya estaba
    suspend fun save(
        me: Learner,
        request: SaveWordRequest,
    ): SavedWord {
        requireLength(request.entryKey, "entryKey", 120)
        requireLength(request.tokenRef, "tokenRef", 32)
        val entry = entries.entry(request.entryKey) ?: throw NotFoundException("Entry not found")
        val into = dictionary.translationLanguage(me, null)
        var surface = entry.lemma
        var context: String? = null
        var readingId: UUID? = null
        var version: Int? = null
        var token: String? = null
        if (request.reading != null) {
            val ref =
                versions.find(request.reading, request.version ?: throw ValidationException("Missing version", "version", "required with reading"))
                    ?: throw NotFoundException("Reading version not found")
            // el contexto sale del texto publicado, nunca del cuerpo de la petición
            val found =
                request.tokenRef?.let { versions.content(ref.versionId).token(it) }
                    ?: throw ValidationException("Invalid token", "tokenRef", "must be a word of that version")
            if (found.second.entry != entry.key) throw ValidationException("Invalid token", "tokenRef", "that word is not ${entry.key}")
            surface = found.second.text
            context = found.first.text
            readingId = ref.readingId
            version = ref.number
            token = found.second.id
        }
        val created = store.insert(me.organizationId, me.userId, entry.key, entry.language, into, surface, context, readingId, version, token)
        val row = store.byKey(me.organizationId, me.userId, entry.key)!!
        if (created) {
            activity.emit(
                LearningActivity(me.organizationId, me.userId, ActivityType.WORD_SAVED, row.savedAt, row.language, "saved:${row.id}", readingId),
            )
        }
        return SavedWord(item(me, row, into), created)
    }

    suspend fun list(
        me: Learner,
        reading: String?,
        status: VocabularyStatus?,
        text: String?,
        page: PageRequest,
    ): Page<VocabularyItem> {
        requireLength(text, "q", 64)
        val into = dictionary.translationLanguage(me, null)
        val (rows, total) = store.list(me.organizationId, me.userId, reading, status, text, page)
        val stored = entries.entries(rows.map { it.entryKey }.distinct()).associateBy { it.key }
        return page.of(rows.map { item(it, stored[it.entryKey]!!, into) }, total)
    }

    suspend fun keys(
        me: Learner,
        language: String?,
    ): List<String> {
        requireLength(language, "language", 8)
        return store.keys(me.organizationId, me.userId, language)
    }

    suspend fun note(
        me: Learner,
        id: UUID,
        note: String?,
    ): VocabularyItem {
        requireLength(note, "note", 2000)
        if (!store.note(me.organizationId, me.userId, id, note)) throw NotFoundException("Word not found")
        return item(me, store.byId(me.organizationId, me.userId, id)!!, dictionary.translationLanguage(me, null))
    }

    suspend fun delete(
        me: Learner,
        id: UUID,
    ) {
        if (!store.delete(me.organizationId, me.userId, id)) throw NotFoundException("Word not found")
    }

    private suspend fun item(
        me: Learner,
        row: VocabularyStore.Row,
        into: String,
    ): VocabularyItem = item(row, entries.entry(row.entryKey)!!, into)

    fun item(
        row: VocabularyStore.Row,
        entry: lesen.dictionary.StoredEntry,
        into: String,
    ) = VocabularyItem(
        id = row.id,
        entryKey = row.entryKey,
        lemma = entry.lemma,
        pos = entry.pos,
        language = row.language,
        translations = dictionary.summary(entry, into, "SAVED").translations,
        surface = row.surface,
        context = row.context,
        tokenRef = row.tokenRef,
        source = if (row.slug != null && row.title != null && row.version != null) VocabularySource(row.slug, row.title, row.version) else null,
        status = row.status,
        note = row.note,
        savedAt = row.savedAt,
        dueAt = row.card.due,
        reviews = row.reviews,
    )
}

@RestController
@RequestMapping("/api/lesen/vocabulary")
class VocabularyController(
    private val access: Access,
    private val service: VocabularyService,
) {
    @GetMapping
    suspend fun list(
        @RequestParam reading: String?,
        @RequestParam status: VocabularyStatus?,
        @RequestParam q: String?,
        @RequestParam page: Int?,
        @RequestParam size: Int?,
    ): Page<VocabularyItem> = service.list(access.notebook(), reading, status, q, PageRequest.of(page, size, 24))

    // las entradas guardadas, para resaltarlas en el texto sin pedir el cuaderno entero
    @GetMapping("/keys")
    suspend fun keys(
        @RequestParam language: String?,
    ): List<String> = service.keys(access.notebook(), language)

    // 201 la primera vez, 200 si ya estaba guardada
    @PostMapping
    suspend fun save(
        @RequestBody request: SaveWordRequest,
    ): ResponseEntity<VocabularyItem> {
        val saved = service.save(access.writeNotebook(), request)
        return ResponseEntity.status(if (saved.created) HttpStatus.CREATED else HttpStatus.OK).body(saved.item)
    }

    @PatchMapping("/{id}")
    suspend fun note(
        @PathVariable id: UUID,
        @RequestBody change: NoteChange,
    ): VocabularyItem = service.note(access.writeNotebook(), id, change.note)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID,
    ) = service.delete(access.writeNotebook(), id)
}
