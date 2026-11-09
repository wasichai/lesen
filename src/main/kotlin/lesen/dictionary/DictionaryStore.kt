package lesen.dictionary

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import lesen.common.Jsonb
import lesen.reading.Tokenizer
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class StoredEntry(
    val key: String,
    val language: String,
    val lemma: String,
    val pos: String,
    val ipa: String?,
    val grammar: Map<String, Any?>,
    val senses: List<Sense>,
)

@Repository
class DictionaryStore(
    private val db: DatabaseClient,
) {
    suspend fun upsert(
        language: String,
        entry: LexiconEntry,
    ) {
        var spec =
            db
                .sql(
                    """
                    INSERT INTO lesen.dictionary_entry (key, language, lemma, pos, ipa, grammar, senses, search)
                    VALUES (:key, :language, :lemma, :pos, :ipa, CAST(:grammar AS jsonb), CAST(:senses AS jsonb), :search)
                    ON CONFLICT (key) DO UPDATE SET lemma = EXCLUDED.lemma, pos = EXCLUDED.pos, ipa = EXCLUDED.ipa,
                      grammar = EXCLUDED.grammar, senses = EXCLUDED.senses, search = EXCLUDED.search
                    """.trimIndent(),
                ).bind("key", entry.key)
                .bind("language", language)
                .bind("lemma", entry.lemma)
                .bind("pos", entry.pos)
                .bind("grammar", Jsonb.of(entry.grammar))
                .bind("senses", Jsonb.of(entry.senses))
                .bind("search", Tokenizer.normalize(entry.lemma))
        spec = if (entry.ipa == null) spec.bindNull("ipa", String::class.java) else spec.bind("ipa", entry.ipa)
        spec.fetch().rowsUpdated().awaitSingle()
        db
            .sql("DELETE FROM lesen.dictionary_form WHERE entry_key = :key")
            .bind("key", entry.key)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql("DELETE FROM lesen.dictionary_translation WHERE entry_key = :key")
            .bind("key", entry.key)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun addForm(
        language: String,
        form: String,
        key: String,
        position: Int,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.dictionary_form (language, form, entry_key, position) VALUES (:l, :f, :k, :p)
                ON CONFLICT (language, form, entry_key) DO UPDATE SET position = LEAST(lesen.dictionary_form.position, EXCLUDED.position)
                """.trimIndent(),
            ).bind("l", language)
            .bind("f", form)
            .bind("k", key)
            .bind("p", position)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun addTranslation(
        key: String,
        language: String,
        term: String,
    ) {
        db
            .sql("INSERT INTO lesen.dictionary_translation (entry_key, language, term) VALUES (:k, :l, :t) ON CONFLICT DO NOTHING")
            .bind("k", key)
            .bind("l", language)
            .bind("t", term)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // forma normalizada → claves de entrada, en el orden de preferencia
    suspend fun forms(language: String): Map<String, List<String>> =
        db
            .sql("SELECT form, entry_key FROM lesen.dictionary_form WHERE language = :l ORDER BY form, position, entry_key")
            .bind("l", language)
            .map { row, _ -> row.get(0, String::class.java)!! to row.get(1, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })

    suspend fun formsOf(key: String): List<String> =
        db
            .sql("SELECT form FROM lesen.dictionary_form WHERE entry_key = :k ORDER BY form")
            .bind("k", key)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun entriesForForm(
        language: String,
        form: String,
    ): List<String> =
        db
            .sql("SELECT entry_key FROM lesen.dictionary_form WHERE language = :l AND form = :f ORDER BY position, entry_key")
            .bind("l", language)
            .bind("f", form)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun entries(keys: Collection<String>): List<StoredEntry> {
        if (keys.isEmpty()) return emptyList()
        val found =
            db
                .sql(
                    "SELECT key, language, lemma, pos, ipa, grammar::text AS grammar, senses::text AS senses FROM lesen.dictionary_entry WHERE key = ANY(:keys)",
                ).bind("keys", keys.toTypedArray())
                .map { row, _ -> stored(row) }
                .all()
                .collectList()
                .awaitSingle()
                .associateBy { it.key }
        return keys.mapNotNull { found[it] }
    }

    suspend fun entry(key: String): StoredEntry? = entries(listOf(key)).firstOrNull()

    // lema o forma que empieza por lo buscado, en el idioma de estudio
    suspend fun searchStudy(
        language: String,
        prefix: String,
        limit: Int,
    ): List<Pair<String, String>> =
        db
            .sql(
                """
                SELECT key, CASE WHEN search LIKE :p THEN 'LEMMA' ELSE 'FORM' END AS matched FROM lesen.dictionary_entry e
                WHERE e.language = :l AND (e.search LIKE :p OR EXISTS (
                  SELECT 1 FROM lesen.dictionary_form f WHERE f.entry_key = e.key AND f.form LIKE :p))
                ORDER BY (e.search = :exact) DESC, (e.search LIKE :p) DESC, length(e.search), e.search
                LIMIT :limit
                """.trimIndent(),
            ).bind("l", language)
            .bind("p", like(prefix))
            .bind("exact", prefix)
            .bind("limit", limit)
            .map { row, _ -> row.get(0, String::class.java)!! to row.get(1, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    // desde el idioma de la persona: entradas del idioma de estudio cuya traducción empieza por lo buscado
    suspend fun searchTranslation(
        studyLanguage: String,
        translationLanguage: String,
        prefix: String,
        limit: Int,
    ): List<String> =
        db
            .sql(
                """
                SELECT e.key FROM lesen.dictionary_translation t JOIN lesen.dictionary_entry e ON e.key = t.entry_key
                WHERE e.language = :study AND t.language = :ui AND t.term LIKE :p
                GROUP BY e.key, e.search ORDER BY bool_or(t.term = :exact) DESC, min(length(t.term)), e.search LIMIT :limit
                """.trimIndent(),
            ).bind("study", studyLanguage)
            .bind("ui", translationLanguage)
            .bind("p", like(prefix))
            .bind("exact", prefix)
            .bind("limit", limit)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun readingsWith(key: String): List<EntryReading> =
        db
            .sql(
                """
                SELECT r.slug, v.title, r.level, e.occurrences FROM lesen.reading_entry e
                JOIN lesen.reading_version v ON v.id = e.version_id
                JOIN lesen.reading r ON r.id = v.reading_id AND r.current_version = v.number
                WHERE e.entry_key = :k ORDER BY e.occurrences DESC, v.title LIMIT 12
                """.trimIndent(),
            ).bind("k", key)
            .map { row, _ ->
                EntryReading(
                    row.get(0, String::class.java)!!,
                    row.get(1, String::class.java)!!,
                    row.get(2, String::class.java)!!,
                    row.get(3, Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun isFavorite(
        o: UUID,
        u: UUID,
        key: String,
    ): Boolean = exists("SELECT 1 FROM lesen.dictionary_favorite WHERE organization_id = :o AND user_id = :u AND entry_key = :k", o, u, key)

    suspend fun isSaved(
        o: UUID,
        u: UUID,
        key: String,
    ): Boolean = exists("SELECT 1 FROM lesen.vocabulary_entry WHERE organization_id = :o AND user_id = :u AND entry_key = :k", o, u, key)

    suspend fun setFavorite(
        o: UUID,
        u: UUID,
        key: String,
        on: Boolean,
    ) {
        val sql =
            if (on) {
                "INSERT INTO lesen.dictionary_favorite (organization_id, user_id, entry_key) VALUES (:o, :u, :k) ON CONFLICT DO NOTHING"
            } else {
                "DELETE FROM lesen.dictionary_favorite WHERE organization_id = :o AND user_id = :u AND entry_key = :k"
            }
        db
            .sql(sql)
            .bind("o", o)
            .bind("u", u)
            .bind("k", key)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun favorites(
        o: UUID,
        u: UUID,
    ): List<String> =
        db
            .sql("SELECT entry_key FROM lesen.dictionary_favorite WHERE organization_id = :o AND user_id = :u ORDER BY created_at DESC")
            .bind("o", o)
            .bind("u", u)
            .map { row, _ -> row.get(0, String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    // guarda la búsqueda y deja solo las últimas HISTORY de esa persona
    suspend fun record(
        o: UUID,
        u: UUID,
        query: String,
        key: String?,
    ) {
        db
            .sql("INSERT INTO lesen.dictionary_search (organization_id, user_id, query, entry_key) VALUES (:o, :u, :q, :k)")
            .bind("o", o)
            .bind("u", u)
            .bind("q", query)
            .let { if (key == null) it.bindNull("k", String::class.java) else it.bind("k", key) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql(
                """
                DELETE FROM lesen.dictionary_search WHERE organization_id = :o AND user_id = :u AND ctid NOT IN (
                  SELECT ctid FROM lesen.dictionary_search WHERE organization_id = :o AND user_id = :u ORDER BY searched_at DESC LIMIT $HISTORY)
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun history(
        o: UUID,
        u: UUID,
    ): List<SearchHistoryItem> =
        db
            .sql(
                """
                SELECT query, (array_agg(entry_key ORDER BY searched_at DESC))[1] AS entry_key, max(searched_at) AS at
                FROM lesen.dictionary_search WHERE organization_id = :o AND user_id = :u
                GROUP BY query ORDER BY at DESC LIMIT 20
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .map { row, _ -> SearchHistoryItem(row.get("query", String::class.java)!!, row.get("entry_key", String::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun clearHistory(
        o: UUID,
        u: UUID,
    ) {
        db
            .sql("DELETE FROM lesen.dictionary_search WHERE organization_id = :o AND user_id = :u")
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun exists(
        sql: String,
        o: UUID,
        u: UUID,
        key: String,
    ): Boolean =
        db
            .sql(sql)
            .bind("o", o)
            .bind("u", u)
            .bind("k", key)
            .map { _, _ -> true }
            .all()
            .collectList()
            .awaitSingle()
            .isNotEmpty()

    private fun stored(row: Readable) =
        StoredEntry(
            row.get("key", String::class.java)!!,
            row.get("language", String::class.java)!!,
            row.get("lemma", String::class.java)!!,
            row.get("pos", String::class.java)!!,
            row.get("ipa", String::class.java),
            Jsonb.read(row.get("grammar", String::class.java)!!),
            Jsonb.read(row.get("senses", String::class.java)!!),
        )

    private fun like(prefix: String) = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    companion object {
        const val HISTORY = 50
    }
}
