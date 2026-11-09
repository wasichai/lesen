package lesen.library

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class Note(
    val id: UUID,
    val version: Int,
    val sentenceRef: String,
    val quote: String,
    val note: String?,
    val createdAt: Instant,
)

// lo personal de la biblioteca: siempre filtrado por (organización, usuario) del token
@Repository
class LibraryStore(
    private val db: DatabaseClient,
) {
    suspend fun progress(
        o: UUID,
        u: UUID,
        reading: UUID,
    ): ProgressState? =
        db
            .sql("SELECT * FROM lesen.reading_progress WHERE organization_id = :o AND user_id = :u AND reading_id = :r")
            .bind("o", o)
            .bind("u", u)
            .bind("r", reading)
            .map { row, _ ->
                ProgressState(
                    row.get("version_number", Number::class.java)!!.toInt(),
                    row.get("position", String::class.java),
                    row.get("percent", Number::class.java)!!.toInt(),
                    row.get("seconds_read", Number::class.java)!!.toInt(),
                    row.get("started_at", Instant::class.java)!!,
                    row.get("updated_at", Instant::class.java)!!,
                    row.get("completed_at", Instant::class.java),
                )
            }.one()
            .awaitSingleOrNull()

    suspend fun saveProgress(
        o: UUID,
        u: UUID,
        reading: UUID,
        s: ProgressState,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.reading_progress
                  (organization_id, user_id, reading_id, version_number, position, percent, seconds_read, started_at, updated_at, completed_at)
                VALUES (:o, :u, :r, :v, :position, :percent, :seconds, :started, :updated, :completed)
                ON CONFLICT (organization_id, user_id, reading_id) DO UPDATE SET
                  version_number = EXCLUDED.version_number, position = EXCLUDED.position, percent = EXCLUDED.percent,
                  seconds_read = EXCLUDED.seconds_read, updated_at = EXCLUDED.updated_at, completed_at = EXCLUDED.completed_at
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("r", reading)
            .bind("v", s.version)
            .let { if (s.position == null) it.bindNull("position", String::class.java) else it.bind("position", s.position) }
            .bind("percent", s.percent)
            .bind("seconds", s.secondsRead)
            .bind("started", s.startedAt)
            .bind("updated", s.updatedAt)
            .let { if (s.completedAt == null) it.bindNull("completed", Instant::class.java) else it.bind("completed", s.completedAt) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun bookmark(
        o: UUID,
        u: UUID,
        reading: UUID,
        on: Boolean,
    ) {
        val sql =
            if (on) {
                "INSERT INTO lesen.reading_bookmark (organization_id, user_id, reading_id) VALUES (:o, :u, :r) ON CONFLICT DO NOTHING"
            } else {
                "DELETE FROM lesen.reading_bookmark WHERE organization_id = :o AND user_id = :u AND reading_id = :r"
            }
        db
            .sql(sql)
            .bind("o", o)
            .bind("u", u)
            .bind("r", reading)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun notes(
        o: UUID,
        u: UUID,
        reading: UUID,
    ): List<Note> =
        db
            .sql(
                """
                SELECT id, version_number, sentence_ref, quote, note, created_at FROM lesen.reading_note
                WHERE organization_id = :o AND user_id = :u AND reading_id = :r ORDER BY created_at
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("r", reading)
            .map { row, _ ->
                Note(
                    row.get("id", UUID::class.java)!!,
                    row.get("version_number", Number::class.java)!!.toInt(),
                    row.get("sentence_ref", String::class.java)!!,
                    row.get("quote", String::class.java)!!,
                    row.get("note", String::class.java),
                    row.get("created_at", Instant::class.java)!!,
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun countNotes(
        o: UUID,
        u: UUID,
        reading: UUID,
    ): Int =
        db
            .sql("SELECT count(*) FROM lesen.reading_note WHERE organization_id = :o AND user_id = :u AND reading_id = :r")
            .bind("o", o)
            .bind("u", u)
            .bind("r", reading)
            .map { row, _ -> row.get(0, Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    suspend fun addNote(
        o: UUID,
        u: UUID,
        reading: UUID,
        version: Int,
        sentenceRef: String,
        quote: String,
        note: String?,
    ): UUID =
        db
            .sql(
                """
                INSERT INTO lesen.reading_note (organization_id, user_id, reading_id, version_number, sentence_ref, quote, note)
                VALUES (:o, :u, :r, :v, :s, :q, :n) RETURNING id
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("r", reading)
            .bind("v", version)
            .bind("s", sentenceRef)
            .bind("q", quote)
            .let { if (note.isNullOrBlank()) it.bindNull("n", String::class.java) else it.bind("n", note.trim()) }
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .one()
            .awaitSingle()

    // false si la nota no existe o es de otra persona: para quien llama son lo mismo (404)
    suspend fun deleteNote(
        o: UUID,
        u: UUID,
        id: UUID,
    ): Boolean =
        db
            .sql("DELETE FROM lesen.reading_note WHERE id = :id AND organization_id = :o AND user_id = :u")
            .bind("id", id)
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
}
