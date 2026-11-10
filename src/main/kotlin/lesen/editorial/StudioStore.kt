package lesen.editorial

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.assessment.Question
import lesen.audio.WordTiming
import lesen.common.Jsonb
import lesen.reading.ReadingDraft
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID

enum class DraftStatus { DRAFT, IN_REVIEW, PUBLISHED, DISCARDED }

// GENERATED: un borrador que escribió la generación (ADR-0015), que se revisa y publica como cualquier otro
enum class StudioAction { CREATED, GENERATED, SAVED, AUDIO_ATTACHED, AUDIO_REMOVED, SUBMITTED, RETURNED, PUBLISHED, DISCARDED }

// el audio de un borrador: un archivo del almacén y sus tiempos, ya casados con las palabras del texto
data class DraftAudio(
    val file: String,
    val format: String,
    val durationMs: Int,
    val voice: String,
    val license: String,
    val timings: List<WordTiming>,
    // lo que el alineador no pudo casar (tramos de varias palabras, palabras dichas que no están escritas)
    val multiWordCues: Int = 0,
    val extraWords: Int = 0,
)

data class StoredDraft(
    val id: UUID,
    val slug: String,
    val language: String,
    val readingId: UUID?,
    val baseVersion: Int?,
    val status: DraftStatus,
    val document: ReadingDraft,
    val questions: List<Question>,
    val audio: DraftAudio?,
    val publishedVersion: Int?,
    val revision: Int,
    val createdBy: String,
    val updatedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StudioEvent(
    val draftId: UUID,
    val slug: String,
    val action: StudioAction,
    val actor: String,
    val note: String?,
    val version: Int?,
    val at: Instant,
)

// una versión publicada tal como la ve el estudio
data class PublishedVersion(
    val number: Int,
    val status: String,
    val title: String,
    val wordCount: Int,
    val publishedAt: Instant,
    val audio: Boolean,
    val questions: Int,
)

data class PublishedReading(
    val slug: String,
    val language: String,
    val level: String,
    val title: String,
    val currentVersion: Int,
    val versions: Int,
    val updatedAt: Instant,
    // el borrador abierto de esta lectura, si lo hay
    val openDraft: UUID?,
)

// los borradores y el historial del estudio. El contenido editorial es de toda la instalación (ADR-0002)
@Repository
class StudioStore(
    private val db: DatabaseClient,
) {
    suspend fun insert(
        slug: String,
        language: String,
        readingId: UUID?,
        baseVersion: Int?,
        document: ReadingDraft,
        questions: List<Question>,
        audio: DraftAudio?,
        actor: String,
    ): UUID =
        db
            .sql(
                """
                INSERT INTO lesen.studio_draft (slug, language, reading_id, base_version, status, document, questions, audio, created_by, updated_by)
                VALUES (:slug, :language, :reading, :base, 'DRAFT', CAST(:document AS jsonb), CAST(:questions AS jsonb), CAST(:audio AS jsonb), :actor, :actor)
                RETURNING id
                """.trimIndent(),
            ).bind("slug", slug)
            .bind("language", language)
            .let { if (readingId == null) it.bindNull("reading", UUID::class.java) else it.bind("reading", readingId) }
            .let { if (baseVersion == null) it.bindNull("base", Integer::class.java) else it.bind("base", baseVersion) }
            .bind("document", Jsonb.of(document))
            .bind("questions", Jsonb.of(questions))
            .let { if (audio == null) it.bindNull("audio", String::class.java) else it.bind("audio", Jsonb.of(audio)) }
            .bind("actor", actor)
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .one()
            .awaitSingle()

    suspend fun find(id: UUID): StoredDraft? =
        db
            .sql("$SELECT WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> draft(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun list(status: DraftStatus?): List<StoredDraft> =
        db
            .sql("$SELECT WHERE (CAST(:status AS text) IS NULL OR status = :status) ORDER BY updated_at DESC LIMIT 200")
            .let { if (status == null) it.bindNull("status", String::class.java) else it.bind("status", status.name) }
            .map { row, _ -> draft(row) }
            .all()
            .collectList()
            .awaitSingle()

    // guarda solo si nadie guardó antes (misma revisión) y si sigue en borrador; false si no
    suspend fun save(
        id: UUID,
        revision: Int,
        document: ReadingDraft,
        questions: List<Question>,
        actor: String,
    ): Boolean =
        db
            .sql(
                """
                UPDATE lesen.studio_draft SET document = CAST(:document AS jsonb), questions = CAST(:questions AS jsonb),
                  revision = revision + 1, updated_by = :actor, updated_at = now()
                WHERE id = :id AND revision = :revision AND status = 'DRAFT'
                """.trimIndent(),
            ).bind("document", Jsonb.of(document))
            .bind("questions", Jsonb.of(questions))
            .bind("actor", actor)
            .bind("id", id)
            .bind("revision", revision)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun setAudio(
        id: UUID,
        audio: DraftAudio?,
        actor: String,
    ): Boolean =
        db
            .sql(
                """
                UPDATE lesen.studio_draft SET audio = CAST(:audio AS jsonb), revision = revision + 1, updated_by = :actor, updated_at = now()
                WHERE id = :id AND status = 'DRAFT'
                """.trimIndent(),
            ).let { if (audio == null) it.bindNull("audio", String::class.java) else it.bind("audio", Jsonb.of(audio)) }
            .bind("actor", actor)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // pasa de un estado a otro solo desde el esperado: dos personas no publican el mismo borrador
    suspend fun move(
        id: UUID,
        from: DraftStatus,
        to: DraftStatus,
        actor: String,
        publishedVersion: Int? = null,
    ): Boolean =
        db
            .sql(
                """
                UPDATE lesen.studio_draft SET status = :to, revision = revision + 1, updated_by = :actor, updated_at = now(),
                  published_version = COALESCE(:version, published_version)
                WHERE id = :id AND status = :from
                """.trimIndent(),
            ).bind("to", to.name)
            .bind("actor", actor)
            .let { if (publishedVersion == null) it.bindNull("version", Integer::class.java) else it.bind("version", publishedVersion) }
            .bind("id", id)
            .bind("from", from.name)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun event(
        draftId: UUID,
        slug: String,
        action: StudioAction,
        actor: String,
        note: String? = null,
        version: Int? = null,
    ) {
        db
            .sql("INSERT INTO lesen.studio_event (draft_id, slug, action, actor, note, version) VALUES (:d, :slug, :action, :actor, :note, :version)")
            .bind("d", draftId)
            .bind("slug", slug)
            .bind("action", action.name)
            .bind("actor", actor)
            .let { if (note.isNullOrBlank()) it.bindNull("note", String::class.java) else it.bind("note", note.trim()) }
            .let { if (version == null) it.bindNull("version", Integer::class.java) else it.bind("version", version) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun events(
        draftId: UUID? = null,
        slug: String? = null,
    ): List<StudioEvent> =
        db
            .sql(
                """
                SELECT * FROM lesen.studio_event
                WHERE (CAST(:d AS uuid) IS NULL OR draft_id = :d) AND (CAST(:slug AS text) IS NULL OR slug = :slug)
                ORDER BY at DESC, id LIMIT 500
                """.trimIndent(),
            ).let { if (draftId == null) it.bindNull("d", UUID::class.java) else it.bind("d", draftId) }
            .let { if (slug == null) it.bindNull("slug", String::class.java) else it.bind("slug", slug) }
            .map { row, _ ->
                StudioEvent(
                    row.get("draft_id", UUID::class.java)!!,
                    row.get("slug", String::class.java)!!,
                    StudioAction.valueOf(row.get("action", String::class.java)!!),
                    row.get("actor", String::class.java)!!,
                    row.get("note", String::class.java),
                    row.get("version", Number::class.java)?.toInt(),
                    row.get("at", Instant::class.java)!!,
                )
            }.all()
            .collectList()
            .awaitSingle()

    // las lecturas publicadas, con su borrador abierto si lo hay (una lectura del modelo de reading, solo lectura)
    suspend fun readings(): List<PublishedReading> =
        db
            .sql(
                """
                SELECT r.slug, r.language, r.level, v.title, r.current_version, v.published_at,
                       (SELECT count(*) FROM lesen.reading_version x WHERE x.reading_id = r.id) AS versions,
                       (SELECT d.id FROM lesen.studio_draft d WHERE d.slug = r.slug AND d.status IN ('DRAFT', 'IN_REVIEW')) AS draft
                FROM lesen.reading r JOIN lesen.reading_version v ON v.reading_id = r.id AND v.number = r.current_version
                WHERE r.owner_user IS NULL
                ORDER BY v.published_at DESC, r.slug
                """.trimIndent(),
            ).map { row, _ ->
                PublishedReading(
                    row.get("slug", String::class.java)!!,
                    row.get("language", String::class.java)!!,
                    row.get("level", String::class.java)!!,
                    row.get("title", String::class.java)!!,
                    row.get("current_version", Number::class.java)!!.toInt(),
                    row.get("versions", Number::class.java)!!.toInt(),
                    row.get("published_at", Instant::class.java)!!,
                    row.get("draft", UUID::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun versions(slug: String): List<PublishedVersion> =
        db
            .sql(
                """
                SELECT v.number, v.status, v.title, v.word_count, v.published_at,
                       EXISTS (SELECT 1 FROM lesen.audio_asset a WHERE a.version_id = v.id) AS audio,
                       COALESCE((SELECT jsonb_array_length(s.questions) FROM lesen.assessment s WHERE s.version_id = v.id), 0) AS questions
                FROM lesen.reading r JOIN lesen.reading_version v ON v.reading_id = r.id
                WHERE r.slug = :slug AND r.owner_user IS NULL ORDER BY v.number DESC
                """.trimIndent(),
            ).bind("slug", slug)
            .map { row, _ ->
                PublishedVersion(
                    row.get("number", Number::class.java)!!.toInt(),
                    row.get("status", String::class.java)!!,
                    row.get("title", String::class.java)!!,
                    row.get("word_count", Number::class.java)!!.toInt(),
                    row.get("published_at", Instant::class.java)!!,
                    row.get("audio", java.lang.Boolean::class.java)!!.booleanValue(),
                    row.get("questions", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    private fun draft(row: Readable) =
        StoredDraft(
            row.get("id", UUID::class.java)!!,
            row.get("slug", String::class.java)!!,
            row.get("language", String::class.java)!!,
            row.get("reading_id", UUID::class.java),
            row.get("base_version", Number::class.java)?.toInt(),
            DraftStatus.valueOf(row.get("status", String::class.java)!!),
            Jsonb.MAPPER.readValue<ReadingDraft>(row.get("document", String::class.java)!!),
            Jsonb.MAPPER.readValue<List<Question>>(row.get("questions", String::class.java)!!),
            row.get("audio", String::class.java)?.let { Jsonb.MAPPER.readValue<DraftAudio>(it) },
            row.get("published_version", Number::class.java)?.toInt(),
            row.get("revision", Number::class.java)!!.toInt(),
            row.get("created_by", String::class.java)!!,
            row.get("updated_by", String::class.java)!!,
            row.get("created_at", Instant::class.java)!!,
            row.get("updated_at", Instant::class.java)!!,
        )

    companion object {
        private const val SELECT =
            "SELECT id, slug, language, reading_id, base_version, status, document::text AS document, questions::text AS questions, " +
                "audio::text AS audio, published_version, revision, created_by, updated_by, created_at, updated_at FROM lesen.studio_draft"
    }
}
