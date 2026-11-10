package lesen.commission

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Jsonb
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class JobTarget { STUDIO, PRIVATE }

enum class JobStatus { QUEUED, WRITING, ILLUSTRATING, READY, FAILED }

data class StoredJob(
    val id: UUID,
    val organizationId: UUID,
    val userId: UUID,
    val actor: String,
    val target: JobTarget,
    val request: CommissionRequest,
    val status: JobStatus,
    val draftId: UUID?,
    val readingSlug: String?,
    val reason: String?,
    val findings: List<Map<String, String?>>,
    val bible: String?,
    val directedBy: String?,
    val usage: Map<String, Long>,
    val attempts: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// la cola de encargos (lesen.generation_job, ADR-0015). Varios procesos la pueden atender a la vez: SKIP LOCKED
@Repository
class CommissionStore(
    private val db: DatabaseClient,
) {
    suspend fun create(
        organizationId: UUID,
        userId: UUID,
        actor: String,
        target: JobTarget,
        request: CommissionRequest,
    ): UUID =
        db
            .sql(
                """
                INSERT INTO lesen.generation_job (organization_id, user_id, actor, target, format, request)
                VALUES (:o, :u, :actor, :target, :format, CAST(:request AS jsonb)) RETURNING id
                """.trimIndent(),
            ).bind("o", organizationId)
            .bind("u", userId)
            .bind("actor", actor)
            .bind("target", target.name)
            .bind("format", request.format.name)
            .bind("request", Jsonb.of(request))
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .one()
            .awaitSingle()

    // el siguiente encargo de la cola, ya marcado como en marcha
    suspend fun claim(): StoredJob? =
        db
            .sql(
                """
                UPDATE lesen.generation_job SET status = 'WRITING', started_at = now(), updated_at = now(), attempts = attempts + 1
                WHERE id = (SELECT id FROM lesen.generation_job WHERE status = 'QUEUED' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING $COLUMNS
                """.trimIndent(),
            ).map { row, _ -> job(row) }
            .one()
            .awaitSingleOrNull()

    // los que se quedaron a medias (el proceso cayó): vuelven a la cola; al tercer intento, fallan
    suspend fun recover(staleMinutes: Long): Long =
        db
            .sql(
                """
                UPDATE lesen.generation_job
                SET status = CASE WHEN attempts >= 3 THEN 'FAILED' ELSE 'QUEUED' END,
                    reason = CASE WHEN attempts >= 3 THEN 'ERROR' ELSE reason END, updated_at = now()
                WHERE status IN ('WRITING', 'ILLUSTRATING') AND updated_at < now() - make_interval(mins => :m)
                """.trimIndent(),
            ).bind("m", staleMinutes.toInt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    suspend fun status(
        id: UUID,
        status: JobStatus,
    ) {
        db
            .sql("UPDATE lesen.generation_job SET status = :s, updated_at = now() WHERE id = :id")
            .bind("s", status.name)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun ready(
        id: UUID,
        draftId: UUID?,
        readingSlug: String?,
        bible: String?,
        directedBy: String?,
        usage: Map<String, Long>,
    ) {
        var spec =
            db
                .sql(
                    """
                    UPDATE lesen.generation_job SET status = 'READY', draft_id = :d, reading_slug = :slug, bible = CAST(:bible AS jsonb),
                      directed_by = :by, usage = CAST(:usage AS jsonb), updated_at = now(), finished_at = now()
                    WHERE id = :id
                    """.trimIndent(),
                ).bind("id", id)
                .bind("usage", Jsonb.of(usage))
        spec = draftId?.let { spec.bind("d", it) } ?: spec.bindNull("d", UUID::class.java)
        spec = readingSlug?.let { spec.bind("slug", it) } ?: spec.bindNull("slug", String::class.java)
        spec = bible?.let { spec.bind("bible", it) } ?: spec.bindNull("bible", String::class.java)
        spec = directedBy?.let { spec.bind("by", it) } ?: spec.bindNull("by", String::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    suspend fun fail(
        id: UUID,
        reason: String,
        findings: List<Map<String, String?>>,
        usage: Map<String, Long>,
    ) {
        db
            .sql(
                """
                UPDATE lesen.generation_job SET status = 'FAILED', reason = :r, findings = CAST(:f AS jsonb), usage = CAST(:usage AS jsonb),
                  updated_at = now(), finished_at = now()
                WHERE id = :id
                """.trimIndent(),
            ).bind("id", id)
            .bind("r", reason)
            .bind("f", Jsonb.of(findings))
            .bind("usage", Jsonb.of(usage))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun find(id: UUID): StoredJob? =
        db
            .sql("SELECT $COLUMNS FROM lesen.generation_job WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> job(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun byDraft(draftId: UUID): StoredJob? =
        db
            .sql("SELECT $COLUMNS FROM lesen.generation_job WHERE draft_id = :d ORDER BY created_at DESC LIMIT 1")
            .bind("d", draftId)
            .map { row, _ -> job(row) }
            .one()
            .awaitSingleOrNull()

    // los de una persona, lo más nuevo primero
    suspend fun mine(
        organizationId: UUID,
        userId: UUID,
        target: JobTarget,
        limit: Int = 50,
    ): List<StoredJob> =
        db
            .sql(
                "SELECT $COLUMNS FROM lesen.generation_job WHERE organization_id = :o AND user_id = :u AND target = :t ORDER BY created_at DESC LIMIT :n",
            ).bind("o", organizationId)
            .bind("u", userId)
            .bind("t", target.name)
            .bind("n", limit)
            .map { row, _ -> job(row) }
            .all()
            .collectList()
            .awaitSingle()

    // cuántos encargó una persona desde un momento, sin contar los que fallaron (un fallo no gasta cuota)
    suspend fun countSince(
        organizationId: UUID,
        userId: UUID,
        target: JobTarget,
        format: lesen.reading.ReadingFormat?,
        since: Instant,
    ): Int =
        db
            .sql(
                """
                SELECT count(*) FROM lesen.generation_job
                WHERE organization_id = :o AND user_id = :u AND target = :t AND created_at >= :since AND status <> 'FAILED'
                  AND (CAST(:format AS text) IS NULL OR format = :format)
                """.trimIndent(),
            ).bind("o", organizationId)
            .bind("u", userId)
            .bind("t", target.name)
            .bind("since", since)
            .let { if (format == null) it.bindNull("format", String::class.java) else it.bind("format", format.name) }
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    // lo que el perfil de escritura necesita de la instalación: palabras por minuto del nivel, temas y lemas del diccionario
    suspend fun wordsPerMinute(level: String): Int? =
        db
            .sql("SELECT words_per_minute FROM lesen.cefr_level WHERE code = :l")
            .bind("l", level)
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
            .one()
            .awaitSingleOrNull()

    suspend fun topics(): List<String> =
        db
            .sql("SELECT slug FROM lesen.topic ORDER BY position, slug")
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun dictionaryLemmas(
        language: String,
        limit: Int,
    ): List<String> =
        db
            .sql("SELECT DISTINCT lemma FROM lesen.dictionary_entry WHERE language = :l ORDER BY lemma LIMIT :n")
            .bind("l", language)
            .bind("n", limit)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    private fun job(row: io.r2dbc.spi.Readable) =
        StoredJob(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            userId = row.get("user_id", UUID::class.java)!!,
            actor = row.get("actor", String::class.java)!!,
            target = JobTarget.valueOf(row.get("target", String::class.java)!!),
            request = Jsonb.read(row.get("request", String::class.java)!!),
            status = JobStatus.valueOf(row.get("status", String::class.java)!!),
            draftId = row.get("draft_id", UUID::class.java),
            readingSlug = row.get("reading_slug", String::class.java),
            reason = row.get("reason", String::class.java),
            findings = row.get("findings", String::class.java)?.let { Jsonb.read(it) } ?: emptyList(),
            bible = row.get("bible", String::class.java),
            directedBy = row.get("directed_by", String::class.java),
            usage = row.get("usage", String::class.java)?.let { Jsonb.read(it) } ?: emptyMap(),
            attempts = row.get("attempts", Number::class.java)!!.toInt(),
            createdAt = row.get("created_at", Instant::class.java)!!,
            updatedAt = row.get("updated_at", Instant::class.java)!!,
        )

    companion object {
        private const val COLUMNS =
            "id, organization_id, user_id, actor, target, request::text AS request, status, draft_id, reading_slug, reason, " +
                "findings::text AS findings, bible::text AS bible, directed_by, usage::text AS usage, attempts, created_at, updated_at"
    }
}
