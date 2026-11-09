package lesen.reading

import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.Jsonb
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class VersionRef(
    val readingId: UUID,
    val versionId: UUID,
    val slug: String,
    val language: String,
    val number: Int,
    val current: Boolean,
)

// lectura de las versiones congeladas: por slug y número, con su contenido y sus traducciones
@Repository
class ReadingVersions(
    private val db: DatabaseClient,
) {
    // la versión pedida, o la vigente si number es null
    suspend fun find(
        slug: String,
        number: Int? = null,
    ): VersionRef? =
        db
            .sql(
                """
                SELECT r.id AS reading_id, v.id AS version_id, r.slug, r.language, v.number, v.number = r.current_version AS current
                FROM lesen.reading r JOIN lesen.reading_version v ON v.reading_id = r.id
                WHERE r.slug = :slug AND v.number = COALESCE(:n, r.current_version)
                """.trimIndent(),
            ).bind("slug", slug)
            .let { if (number == null) it.bindNull("n", Integer::class.java) else it.bind("n", number) }
            .map { row, _ ->
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
}
