package lesen.reading

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Jsonb
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.util.UUID

data class PublishResult(
    val readingId: UUID,
    val version: Int,
    val created: Boolean,
    val unresolved: Set<String>,
)

// publica una lectura: valida, tokeniza, resuelve contra el diccionario y congela una versión nueva (ADR-0003). Si el
// contenido no cambió desde la versión vigente, no crea otra (idempotente). Las versiones anteriores quedan RETIRED pero
// legibles: el progreso, las notas y el vocabulario de quien las leyó siguen apuntando a ellas
@Service
class ReadingPublisher(
    private val db: DatabaseClient,
    private val tx: TransactionalOperator,
    private val resolvers: FormResolvers,
) {
    private val tokenizers = Tokenizers()
    private val log = LoggerFactory.getLogger(ReadingPublisher::class.java)

    suspend fun publish(draft: ReadingDraft): PublishResult {
        ReadingAssembler.validate(draft)
        requireReferences(draft)
        val assembled = ReadingAssembler.assemble(draft, tokenizers.forLanguage(draft.language), resolvers.load(draft.language))
        val wpm =
            db
                .sql("SELECT words_per_minute FROM lesen.cefr_level WHERE code = :level")
                .bind("level", draft.level)
                .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
                .one()
                .awaitSingle()
        val result =
            tx.executeAndAwait {
                val readingId = upsertReading(draft)
                val current = currentVersion(readingId)
                if (current != null && current.second == assembled.hash) {
                    PublishResult(readingId, current.first, false, assembled.unresolved)
                } else {
                    val number = (current?.first ?: 0) + 1
                    val versionId = insertVersion(readingId, number, draft, assembled, ReadingTime.minutes(assembled.wordCount, wpm))
                    db
                        .sql("UPDATE lesen.reading_version SET status = 'RETIRED' WHERE reading_id = :r AND number < :n AND status = 'PUBLISHED'")
                        .bind("r", readingId)
                        .bind("n", number)
                        .fetch()
                        .rowsUpdated()
                        .awaitSingle()
                    db
                        .sql("UPDATE lesen.reading SET current_version = :n WHERE id = :r")
                        .bind("n", number)
                        .bind("r", readingId)
                        .fetch()
                        .rowsUpdated()
                        .awaitSingle()
                    insertTranslations(versionId, assembled.translations)
                    insertOccurrences(versionId, assembled.entryOccurrences)
                    PublishResult(readingId, number, true, assembled.unresolved)
                }
            }
        if (result.created && result.unresolved.isNotEmpty()) {
            log.warn("lectura {} v{}: {} formas sin entrada en el diccionario", draft.slug, result.version, result.unresolved.size)
        }
        return result
    }

    private suspend fun requireReferences(draft: ReadingDraft) {
        val language =
            db
                .sql("SELECT study FROM lesen.language WHERE code = :code")
                .bind("code", draft.language)
                .map { row, _ -> row.get(0, java.lang.Boolean::class.java)!!.booleanValue() }
                .one()
                .awaitSingleOrNull()
        if (language != true) throw ValidationException("Invalid reading", "language", "must be a language that can be studied")
        val known =
            db
                .sql("SELECT slug FROM lesen.topic WHERE slug = ANY(:slugs)")
                .bind("slugs", draft.topics.toTypedArray())
                .map { row, _ -> row.get(0, String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
                .toSet()
        val missing = draft.topics.filter { it !in known }
        if (missing.isNotEmpty()) throw ValidationException("Invalid reading", listOf(FieldViolation("topics", "unknown topics: $missing")))
    }

    private suspend fun upsertReading(draft: ReadingDraft): UUID {
        val id =
            db
                .sql(
                    """
                    INSERT INTO lesen.reading (slug, language, level, featured) VALUES (:slug, :language, :level, :featured)
                    ON CONFLICT (slug) DO UPDATE SET level = EXCLUDED.level, featured = EXCLUDED.featured
                    WHERE lesen.reading.language = EXCLUDED.language
                    RETURNING id
                    """.trimIndent(),
                ).bind("slug", draft.slug)
                .bind("language", draft.language)
                .bind("level", draft.level)
                .bind("featured", draft.featured)
                .map { row, _ -> row.get(0, UUID::class.java)!! }
                .one()
                .awaitSingleOrNull()
                ?: throw ValidationException("Invalid reading", "language", "a reading keeps the language it was first published in")
        db
            .sql("DELETE FROM lesen.reading_topic WHERE reading_id = :r")
            .bind("r", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        draft.topics.forEachIndexed { i, topic ->
            db
                .sql("INSERT INTO lesen.reading_topic (reading_id, topic_slug, position) VALUES (:r, :t, :p)")
                .bind("r", id)
                .bind("t", topic)
                .bind("p", i)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        return id
    }

    private suspend fun currentVersion(readingId: UUID): Pair<Int, String>? =
        db
            .sql(
                """
                SELECT v.number, v.content_hash FROM lesen.reading r
                JOIN lesen.reading_version v ON v.reading_id = r.id AND v.number = r.current_version
                WHERE r.id = :r
                """.trimIndent(),
            ).bind("r", readingId)
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() to row.get(1, String::class.java)!! }
            .one()
            .awaitSingleOrNull()

    private suspend fun insertVersion(
        readingId: UUID,
        number: Int,
        draft: ReadingDraft,
        assembled: AssembledVersion,
        minutes: Int,
    ): UUID {
        var spec =
            db
                .sql(
                    """
                    INSERT INTO lesen.reading_version
                      (reading_id, number, status, title, subtitle, summary, cover, accent, word_count, minutes, content, content_hash)
                    VALUES (:r, :n, 'PUBLISHED', :title, :subtitle, :summary, :cover, :accent, :words, :minutes, CAST(:content AS jsonb), :hash)
                    RETURNING id
                    """.trimIndent(),
                ).bind("r", readingId)
                .bind("n", number)
                .bind("title", draft.title.trim())
                .bind("summary", draft.summary.trim())
                .bind("words", assembled.wordCount)
                .bind("minutes", minutes)
                .bind("content", Jsonb.of(assembled.content))
                .bind("hash", assembled.hash)
        spec = draft.subtitle?.let { spec.bind("subtitle", it.trim()) } ?: spec.bindNull("subtitle", String::class.java)
        spec = draft.cover?.let { spec.bind("cover", it) } ?: spec.bindNull("cover", String::class.java)
        spec = draft.accent?.let { spec.bind("accent", it) } ?: spec.bindNull("accent", String::class.java)
        return spec.map { row, _ -> row.get(0, UUID::class.java)!! }.one().awaitSingle()
    }

    private suspend fun insertTranslations(
        versionId: UUID,
        units: List<TranslationUnit>,
    ) {
        for (unit in units) {
            db
                .sql(
                    """
                    INSERT INTO lesen.reading_translation (version_id, unit, ref, language, text, source)
                    SELECT :v, :unit, :ref, :language, :text, 'AUTHORED' WHERE EXISTS (SELECT 1 FROM lesen.language WHERE code = :language)
                    """.trimIndent(),
                ).bind("v", versionId)
                .bind("unit", unit.unit)
                .bind("ref", unit.ref)
                .bind("language", unit.language)
                .bind("text", unit.text.trim())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private suspend fun insertOccurrences(
        versionId: UUID,
        occurrences: Map<String, Int>,
    ) {
        for ((entry, count) in occurrences) {
            db
                .sql("INSERT INTO lesen.reading_entry (version_id, entry_key, occurrences) VALUES (:v, :e, :c)")
                .bind("v", versionId)
                .bind("e", entry)
                .bind("c", count)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }
}

// el resolutor de formas de cada idioma; lo implementa el diccionario (lesen.dictionary), que depende de reading y no
// al revés
fun interface FormResolvers {
    suspend fun load(language: String): FormResolver
}
