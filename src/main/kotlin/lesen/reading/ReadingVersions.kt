package lesen.reading

import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Jsonb
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.UnauthorizedException
import wasichai.core.identity.CurrentUser
import java.util.UUID

data class VersionRef(
    val readingId: UUID,
    val versionId: UUID,
    val slug: String,
    val language: String,
    val number: Int,
    val current: Boolean,
)

// lo que acompaña al texto de una versión, para volver a editarla en el estudio
data class VersionMeta(
    val level: String,
    val featured: Boolean,
    val topics: List<String>,
    val title: String,
    val subtitle: String?,
    val summary: String,
    val cover: String?,
    val accent: String?,
    val hash: String,
)

// lectura de las versiones congeladas: por slug y número, con su contenido y sus traducciones
@Repository
class ReadingVersions(
    private val db: DatabaseClient,
    private val currentUser: CurrentUser,
) {
    // la versión pedida, o la vigente si number es null. Una lectura a medida (ADR-0016) solo la encuentra quien la pidió:
    // para cualquier otra persona, y sin nadie que llame, no existe (404)
    suspend fun find(
        slug: String,
        number: Int? = null,
    ): VersionRef? {
        val viewer =
            try {
                currentUser.require()
            } catch (_: UnauthorizedException) {
                null
            }
        return db
            .sql(
                """
                SELECT r.id AS reading_id, v.id AS version_id, r.slug, r.language, v.number, v.number = r.current_version AS current
                FROM lesen.reading r JOIN lesen.reading_version v ON v.reading_id = r.id
                WHERE r.slug = :slug AND v.number = COALESCE(:n, r.current_version)
                  AND (r.owner_user IS NULL OR (r.owner_organization = :o AND r.owner_user = :u))
                """.trimIndent(),
            ).bind("slug", slug)
            .let { if (number == null) it.bindNull("n", Integer::class.java) else it.bind("n", number) }
            .let {
                if (viewer ==
                    null
                ) {
                    it.bindNull("o", UUID::class.java).bindNull("u", UUID::class.java)
                } else {
                    it.bind("o", viewer.organizationId).bind("u", viewer.userId)
                }
            }.map { row, _ ->
                VersionRef(
                    row.get("reading_id", UUID::class.java)!!,
                    row.get("version_id", UUID::class.java)!!,
                    row.get("slug", String::class.java)!!,
                    row.get("language", String::class.java)!!,
                    row.get("number", Number::class.java)!!.toInt(),
                    row.get("current", java.lang.Boolean::class.java)!!.booleanValue(),
                )
            }.one()
            .awaitSingleOrNull()
    }

    // el id de una versión por su lectura, sin mirar quién llama: para quien publica en segundo plano (una lectura a medida)
    suspend fun versionId(
        readingId: UUID,
        number: Int,
    ): UUID? =
        db
            .sql("SELECT id FROM lesen.reading_version WHERE reading_id = :r AND number = :n")
            .bind("r", readingId)
            .bind("n", number)
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .one()
            .awaitSingleOrNull()

    suspend fun content(versionId: UUID): ReadingContent =
        db
            .sql("SELECT content::text FROM lesen.reading_version WHERE id = :v")
            .bind("v", versionId)
            .map { row, _ -> Jsonb.read<ReadingContent>(row.get(0, String::class.java)!!) }
            .one()
            .awaitSingleOrNull() ?: error("version $versionId without content")

    suspend fun translations(
        versionId: UUID,
        unit: String,
        language: String,
        ref: String?,
    ): List<TranslationUnit> =
        db
            .sql(
                """
                SELECT unit, ref, language, text FROM lesen.reading_translation
                WHERE version_id = :v AND unit = :unit AND language = :language AND (CAST(:ref AS text) IS NULL OR ref = :ref)
                ORDER BY ref
                """.trimIndent(),
            ).bind("v", versionId)
            .bind("unit", unit)
            .bind("language", language)
            .let { if (ref == null) it.bindNull("ref", String::class.java) else it.bind("ref", ref) }
            .map { row, _ ->
                TranslationUnit(
                    row.get("unit", String::class.java)!!,
                    row.get("ref", String::class.java)!!,
                    row.get("language", String::class.java)!!,
                    row.get("text", String::class.java)!!,
                )
            }.all()
            .collectList()
            .awaitSingleOrNull()
            .orEmpty()

    suspend fun meta(versionId: UUID): VersionMeta? =
        db
            .sql(
                """
                SELECT r.level, r.featured, v.title, v.subtitle, v.summary, v.cover, v.accent, v.content_hash,
                       (SELECT array_agg(t.topic_slug ORDER BY t.position) FROM lesen.reading_topic t WHERE t.reading_id = r.id) AS topics
                FROM lesen.reading_version v JOIN lesen.reading r ON r.id = v.reading_id WHERE v.id = :v
                """.trimIndent(),
            ).bind("v", versionId)
            .map { row, _ ->
                VersionMeta(
                    row.get("level", String::class.java)!!,
                    row.get("featured", java.lang.Boolean::class.java)!!.booleanValue(),
                    (row.get("topics", Array<String>::class.java) ?: emptyArray()).toList(),
                    row.get("title", String::class.java)!!,
                    row.get("subtitle", String::class.java),
                    row.get("summary", String::class.java)!!,
                    row.get("cover", String::class.java),
                    row.get("accent", String::class.java),
                    row.get("content_hash", String::class.java)!!,
                )
            }.one()
            .awaitSingleOrNull()
}
