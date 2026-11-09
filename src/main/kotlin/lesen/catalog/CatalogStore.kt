package lesen.catalog

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import lesen.common.Jsonb
import lesen.common.PageRequest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

// las consultas del catálogo. Una tarjeta une la versión vigente con el progreso y el marcador de quien pregunta: es
// un modelo de lectura que cruza las tablas de library a propósito, para no hacer una consulta por tarjeta
@Repository
class CatalogStore(
    private val db: DatabaseClient,
) {
    suspend fun topics(): List<Topic> =
        db
            .sql("SELECT slug, parent_slug, labels::text AS labels, position FROM lesen.topic ORDER BY position, slug")
            .map { row, _ ->
                Topic(
                    row.get("slug", String::class.java)!!,
                    row.get("parent_slug", String::class.java),
                    Jsonb.read(row.get("labels", String::class.java)!!),
                    row.get("position", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun cards(
        organization: UUID,
        user: UUID,
        query: CatalogQuery,
        page: PageRequest,
    ): Pair<List<ReadingCard>, Long> {
        val (where, binds) = filters(query)
        val order =
            when (query.sort) {
                ReadingSort.RECENT -> "v.published_at DESC, r.slug"
                ReadingSort.POPULAR -> "readers DESC, v.published_at DESC, r.slug"
                ReadingSort.LEVEL -> "l.position, v.title"
                ReadingSort.DURATION -> "v.minutes, v.title"
                ReadingSort.TITLE -> "v.title, r.slug"
            }
        var spec =
            db
                .sql("$CARD_SELECT WHERE $where ORDER BY $order LIMIT :limit OFFSET :offset")
                .bind("o", organization)
                .bind("u", user)
                .bind("limit", page.size)
                .bind("offset", page.offset)
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        val content =
            spec
                .map { row, _ -> card(row) }
                .all()
                .collectList()
                .awaitSingle()
        var count =
            db
                .sql("SELECT count(*) FROM ($CARD_SELECT WHERE $where) AS c")
                .bind("o", organization)
                .bind("u", user)
        binds.forEach { (k, v) -> count = count.bind(k, v) }
        val total = count.map { row, _ -> row.get(0, Number::class.java)!!.toLong() }.one().awaitSingle()
        return content to total
    }

    suspend fun card(
        organization: UUID,
        user: UUID,
        slug: String,
    ): ReadingCard? =
        db
            .sql("$CARD_SELECT WHERE r.slug = :slug")
            .bind("o", organization)
            .bind("u", user)
            .bind("slug", slug)
            .map { row, _ -> card(row) }
            .all()
            .collectList()
            .awaitSingle()
            .firstOrNull()

    suspend fun paragraphs(slug: String): Int =
        db
            .sql(
                """
                SELECT jsonb_array_length(v.content -> 'paragraphs') FROM lesen.reading r
                JOIN lesen.reading_version v ON v.reading_id = r.id AND v.number = r.current_version WHERE r.slug = :slug
                """.trimIndent(),
            ).bind("slug", slug)
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    suspend fun collections(): List<Triple<String, Map<String, String>, Map<String, String>>> =
        db
            .sql("SELECT slug, labels::text AS labels, descriptions::text AS descriptions FROM lesen.collection ORDER BY position, slug")
            .map { row, _ ->
                Triple(
                    row.get("slug", String::class.java)!!,
                    Jsonb.read<Map<String, String>>(row.get("labels", String::class.java)!!),
                    Jsonb.read<Map<String, String>>(row.get("descriptions", String::class.java)!!),
                )
            }.all()
            .collectList()
            .awaitSingle()

    // slug de colección → slugs de lectura en orden
    suspend fun collectionReadings(): Map<String, List<String>> =
        db
            .sql(
                """
                SELECT c.collection_slug, r.slug FROM lesen.collection_reading c JOIN lesen.reading r ON r.id = c.reading_id
                ORDER BY c.collection_slug, c.position
                """.trimIndent(),
            ).map { row, _ -> row.get(0, String::class.java)!! to row.get(1, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })

    // lecturas por tema principal (una lectura de travel/coasts cuenta en travel)
    suspend fun topicCounts(language: String?): List<TopicCount> =
        db
            .sql(
                """
                SELECT split_part(t.topic_slug, '/', 1) AS root, count(DISTINCT r.id) FROM lesen.reading_topic t
                JOIN lesen.reading r ON r.id = t.reading_id AND r.current_version IS NOT NULL
                WHERE CAST(:language AS text) IS NULL OR r.language = :language
                GROUP BY root ORDER BY count(DISTINCT r.id) DESC, root
                """.trimIndent(),
            ).let { if (language == null) it.bindNull("language", String::class.java) else it.bind("language", language) }
            .map { row, _ -> TopicCount(row.get(0, String::class.java)!!, row.get(1, Number::class.java)!!.toInt()) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun saveTopic(topic: Topic) {
        db
            .sql(
                """
                INSERT INTO lesen.topic (slug, parent_slug, labels, position) VALUES (:slug, :parent, CAST(:labels AS jsonb), :position)
                ON CONFLICT (slug) DO UPDATE SET parent_slug = EXCLUDED.parent_slug, labels = EXCLUDED.labels, position = EXCLUDED.position
                """.trimIndent(),
            ).bind("slug", topic.slug)
            .let { if (topic.parent == null) it.bindNull("parent", String::class.java) else it.bind("parent", topic.parent) }
            .bind("labels", Jsonb.of(topic.labels))
            .bind("position", topic.position)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun saveCollection(
        slug: String,
        labels: Map<String, String>,
        descriptions: Map<String, String>,
        position: Int,
        readings: List<String>,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.collection (slug, labels, descriptions, position) VALUES (:slug, CAST(:labels AS jsonb), CAST(:descriptions AS jsonb), :position)
                ON CONFLICT (slug) DO UPDATE SET labels = EXCLUDED.labels, descriptions = EXCLUDED.descriptions, position = EXCLUDED.position
                """.trimIndent(),
            ).bind("slug", slug)
            .bind("labels", Jsonb.of(labels))
            .bind("descriptions", Jsonb.of(descriptions))
            .bind("position", position)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql("DELETE FROM lesen.collection_reading WHERE collection_slug = :slug")
            .bind("slug", slug)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        readings.forEachIndexed { i, reading ->
            db
                .sql(
                    """
                    INSERT INTO lesen.collection_reading (collection_slug, reading_id, position)
                    SELECT :slug, id, :position FROM lesen.reading WHERE slug = :reading
                    """.trimIndent(),
                ).bind("slug", slug)
                .bind("position", i)
                .bind("reading", reading)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun filters(query: CatalogQuery): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf("r.current_version IS NOT NULL")
        val binds = mutableMapOf<String, Any>()
        query.language?.let {
            clauses += "r.language = :language"
            binds["language"] = it
        }
        if (query.levels.isNotEmpty()) {
            clauses += "r.level = ANY(:levels)"
            binds["levels"] = query.levels.toTypedArray()
        }
        query.topic?.let {
            clauses += "EXISTS (SELECT 1 FROM lesen.reading_topic t WHERE t.reading_id = r.id AND (t.topic_slug = :topic OR t.topic_slug LIKE :topicPrefix))"
            binds["topic"] = it
            binds["topicPrefix"] = "${it.replace("%", "").replace("_", "\\_")}/%"
        }
        query.minMinutes?.let {
            clauses += "v.minutes >= :minMinutes"
            binds["minMinutes"] = it
        }
        query.maxMinutes?.let {
            clauses += "v.minutes <= :maxMinutes"
            binds["maxMinutes"] = it
        }
        query.featured?.let {
            clauses += "r.featured = :featured"
            binds["featured"] = it
        }
        query.text?.takeIf { it.isNotBlank() }?.let {
            clauses += "(v.title ILIKE :text OR v.subtitle ILIKE :text OR v.summary ILIKE :text)"
            binds["text"] = "%" +
                it
                    .trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%"
        }
        when (query.status) {
            ReadingStatus.BOOKMARKED -> clauses += "b.reading_id IS NOT NULL"
            ReadingStatus.IN_PROGRESS -> clauses += "p.reading_id IS NOT NULL AND p.completed_at IS NULL"
            ReadingStatus.COMPLETED -> clauses += "p.completed_at IS NOT NULL"
            ReadingStatus.NOT_STARTED -> clauses += "p.reading_id IS NULL"
            null -> {}
        }
        return clauses.joinToString(" AND ") to binds
    }

    private fun card(row: Readable): ReadingCard {
        val progressAt = row.get("progress_at", Instant::class.java)
        return ReadingCard(
            slug = row.get("slug", String::class.java)!!,
            language = row.get("language", String::class.java)!!,
            level = row.get("level", String::class.java)!!,
            topics = (row.get("topics", Array<String>::class.java) ?: emptyArray()).toList(),
            featured = row.get("featured", java.lang.Boolean::class.java)!!.booleanValue(),
            version = row.get("number", Number::class.java)!!.toInt(),
            title = row.get("title", String::class.java)!!,
            subtitle = row.get("subtitle", String::class.java),
            summary = row.get("summary", String::class.java)!!,
            cover = row.get("cover", String::class.java)?.let { "$COVERS/$it" },
            accent = row.get("accent", String::class.java),
            wordCount = row.get("word_count", Number::class.java)!!.toInt(),
            minutes = row.get("minutes", Number::class.java)!!.toInt(),
            publishedAt = row.get("published_at", Instant::class.java)!!,
            bookmarked = row.get("bookmarked", java.lang.Boolean::class.java)!!.booleanValue(),
            progress =
                progressAt?.let {
                    CardProgress(
                        row.get("percent", Number::class.java)!!.toInt(),
                        row.get("progress_version", Number::class.java)!!.toInt(),
                        row.get("completed_at", Instant::class.java) != null,
                        it,
                    )
                },
            readers = row.get("readers", Number::class.java)!!.toLong(),
        )
    }

    companion object {
        const val COVERS = "/api/lesen/public/covers"

        private val CARD_SELECT =
            """
            SELECT r.slug, r.language, r.level, r.featured, v.number, v.title, v.subtitle, v.summary, v.cover, v.accent,
                   v.word_count, v.minutes, v.published_at,
                   (SELECT array_agg(t.topic_slug ORDER BY t.position) FROM lesen.reading_topic t WHERE t.reading_id = r.id) AS topics,
                   p.percent, p.version_number AS progress_version, p.completed_at, p.updated_at AS progress_at,
                   (b.reading_id IS NOT NULL) AS bookmarked,
                   (SELECT count(*) FROM lesen.reading_progress pp WHERE pp.reading_id = r.id) AS readers
            FROM lesen.reading r
            JOIN lesen.reading_version v ON v.reading_id = r.id AND v.number = r.current_version
            JOIN lesen.cefr_level l ON l.code = r.level
            LEFT JOIN lesen.reading_progress p ON p.reading_id = r.id AND p.organization_id = :o AND p.user_id = :u
            LEFT JOIN lesen.reading_bookmark b ON b.reading_id = r.id AND b.organization_id = :o AND b.user_id = :u
            """.trimIndent()
    }
}
