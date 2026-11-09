package lesen.progress

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import lesen.common.ActivityType
import lesen.common.LearningActivity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ProgressSettings(
    val weeklyDays: Int = 3,
    val timeZone: String = "UTC",
    val weeklySummary: Boolean = false,
    // false hasta que la persona (o su navegador) guarda sus metas: la interfaz pone entonces la zona del dispositivo
    val configured: Boolean = false,
)

// lo personal del progreso: siempre filtrado por (organización, usuario) del token
@Repository
class ProgressStore(
    private val db: DatabaseClient,
) {
    // false si ya estaba (misma clave): el evento no vuelve a contar
    suspend fun insert(a: LearningActivity): Boolean =
        db
            .sql(
                """
                INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, reading_id, seconds, words, dedup_key)
                VALUES (:o, :u, :type, :at, :language, :reading, :seconds, :words, :key)
                ON CONFLICT (organization_id, user_id, dedup_key) DO NOTHING RETURNING id
                """.trimIndent(),
            ).bind("o", a.organizationId)
            .bind("u", a.userId)
            .bind("type", a.type.name)
            .bind("at", a.at)
            .bind("language", a.language)
            .let { if (a.readingId == null) it.bindNull("reading", UUID::class.java) else it.bind("reading", a.readingId) }
            .bind("seconds", a.seconds)
            .bind("words", a.words)
            .let { if (a.key == null) it.bindNull("key", String::class.java) else it.bind("key", a.key) }
            .map { row, _ -> row.get(0, UUID::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .isNotEmpty()

    suspend fun activities(
        o: UUID,
        u: UUID,
    ): List<LearningActivity> =
        db
            .sql("SELECT * FROM lesen.learning_activity WHERE organization_id = :o AND user_id = :u ORDER BY occurred_at, id")
            .bind("o", o)
            .bind("u", u)
            .map { row, _ ->
                LearningActivity(
                    o,
                    u,
                    ActivityType.valueOf(row.get("type", String::class.java)!!),
                    row.get("occurred_at", Instant::class.java)!!,
                    row.get("language", String::class.java)!!,
                    row.get("dedup_key", String::class.java),
                    row.get("reading_id", UUID::class.java),
                    row.get("seconds", Number::class.java)!!.toInt(),
                    row.get("words", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    // el incremento de un evento sobre su día
    suspend fun addDay(
        o: UUID,
        u: UUID,
        key: DayKey,
        t: Totals,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.daily_progress AS d
                  (organization_id, user_id, day, language, seconds, readings_started, readings_completed, words_read, words_saved, words_reviewed, assessments)
                VALUES (:o, :u, :day, :language, :seconds, :started, :completed, :read, :saved, :reviewed, :assessments)
                ON CONFLICT (organization_id, user_id, day, language) DO UPDATE SET
                  seconds = d.seconds + EXCLUDED.seconds,
                  readings_started = d.readings_started + EXCLUDED.readings_started,
                  readings_completed = d.readings_completed + EXCLUDED.readings_completed,
                  words_read = d.words_read + EXCLUDED.words_read,
                  words_saved = d.words_saved + EXCLUDED.words_saved,
                  words_reviewed = d.words_reviewed + EXCLUDED.words_reviewed,
                  assessments = d.assessments + EXCLUDED.assessments
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("day", key.day)
            .bind("language", key.language)
            .bind("seconds", t.seconds)
            .bind("started", t.readingsStarted)
            .bind("completed", t.readingsCompleted)
            .bind("read", t.wordsRead)
            .bind("saved", t.wordsSaved)
            .bind("reviewed", t.wordsReviewed)
            .bind("assessments", t.assessments)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun clearDays(
        o: UUID,
        u: UUID,
    ) {
        db
            .sql("DELETE FROM lesen.daily_progress WHERE organization_id = :o AND user_id = :u")
            .bind("o", o)
            .bind("u", u)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // las filas de la proyección entre dos días (inclusivos), de un idioma o de todos
    suspend fun days(
        o: UUID,
        u: UUID,
        from: LocalDate?,
        to: LocalDate?,
        language: String? = null,
    ): Map<DayKey, Totals> =
        db
            .sql(
                """
                SELECT * FROM lesen.daily_progress WHERE organization_id = :o AND user_id = :u
                  AND (CAST(:from AS date) IS NULL OR day >= :from) AND (CAST(:to AS date) IS NULL OR day <= :to)
                  AND (CAST(:l AS text) IS NULL OR language = :l)
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .let { if (from == null) it.bindNull("from", LocalDate::class.java) else it.bind("from", from) }
            .let { if (to == null) it.bindNull("to", LocalDate::class.java) else it.bind("to", to) }
            .let { if (language == null) it.bindNull("l", String::class.java) else it.bind("l", language) }
            .map { row, _ -> DayKey(row.get("day", LocalDate::class.java)!!, row.get("language", String::class.java)!!) to totals(row) }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    suspend fun settings(
        o: UUID,
        u: UUID,
    ): ProgressSettings =
        db
            .sql("SELECT weekly_days, time_zone, weekly_summary FROM lesen.progress_settings WHERE organization_id = :o AND user_id = :u")
            .bind("o", o)
            .bind("u", u)
            .map { row, _ ->
                ProgressSettings(
                    row.get("weekly_days", Number::class.java)!!.toInt(),
                    row.get("time_zone", String::class.java)!!,
                    row.get("weekly_summary", java.lang.Boolean::class.java)!!.booleanValue(),
                    true,
                )
            }.one()
            .awaitSingleOrNull() ?: ProgressSettings()

    suspend fun saveSettings(
        o: UUID,
        u: UUID,
        s: ProgressSettings,
    ) {
        db
            .sql(
                """
                INSERT INTO lesen.progress_settings (organization_id, user_id, weekly_days, time_zone, weekly_summary)
                VALUES (:o, :u, :days, :zone, :summary)
                ON CONFLICT (organization_id, user_id) DO UPDATE SET
                  weekly_days = EXCLUDED.weekly_days, time_zone = EXCLUDED.time_zone, weekly_summary = EXCLUDED.weekly_summary, updated_at = now()
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("days", s.weeklyDays)
            .bind("zone", s.timeZone)
            .bind("summary", s.weeklySummary)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun rules(): List<AchievementRule> =
        db
            .sql("SELECT code, kind, threshold, position FROM lesen.achievement ORDER BY position, code")
            .map { row, _ ->
                AchievementRule(
                    row.get("code", String::class.java)!!,
                    AchievementKind.valueOf(row.get("kind", String::class.java)!!),
                    row.get("threshold", Number::class.java)!!.toInt(),
                    row.get("position", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun earned(
        o: UUID,
        u: UUID,
    ): Map<String, Instant> =
        db
            .sql("SELECT code, earned_at FROM lesen.learner_achievement WHERE organization_id = :o AND user_id = :u")
            .bind("o", o)
            .bind("u", u)
            .map { row, _ -> row.get("code", String::class.java)!! to row.get("earned_at", Instant::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    // una sola vez por persona y logro
    suspend fun grant(
        o: UUID,
        u: UUID,
        codes: List<String>,
        at: Instant,
    ) {
        if (codes.isEmpty()) return
        db
            .sql(
                """
                INSERT INTO lesen.learner_achievement (organization_id, user_id, code, earned_at)
                SELECT :o, :u, c, :at FROM unnest(CAST(:codes AS text[])) AS c ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).bind("o", o)
            .bind("u", u)
            .bind("codes", codes.toTypedArray())
            .bind("at", at)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // cuántas personas de la organización terminaron cada lectura desde una fecha: un agregado, nunca quiénes
    suspend fun trending(
        o: UUID,
        language: String,
        since: Instant,
    ): Map<String, Int> =
        db
            .sql(
                """
                SELECT r.slug, count(DISTINCT a.user_id) AS readers FROM lesen.learning_activity a
                JOIN lesen.reading r ON r.id = a.reading_id
                WHERE a.organization_id = :o AND a.type = 'READING_COMPLETED' AND a.occurred_at >= :since AND r.language = :l
                GROUP BY r.slug
                """.trimIndent(),
            ).bind("o", o)
            .bind("since", since)
            .bind("l", language)
            .map { row, _ -> row.get("slug", String::class.java)!! to row.get("readers", Number::class.java)!!.toInt() }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    private fun totals(row: Readable) =
        Totals(
            row.get("seconds", Number::class.java)!!.toInt(),
            row.get("readings_started", Number::class.java)!!.toInt(),
            row.get("readings_completed", Number::class.java)!!.toInt(),
            row.get("words_read", Number::class.java)!!.toInt(),
            row.get("words_saved", Number::class.java)!!.toInt(),
            row.get("words_reviewed", Number::class.java)!!.toInt(),
            row.get("assessments", Number::class.java)!!.toInt(),
        )
}
