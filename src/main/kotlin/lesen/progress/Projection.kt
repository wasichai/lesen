package lesen.progress

import lesen.common.ActivityType
import lesen.common.LearningActivity
import java.time.LocalDate
import java.time.ZoneId

// lo de un día (o de un periodo): la suma de lo que hizo la persona
data class Totals(
    val seconds: Int = 0,
    val readingsStarted: Int = 0,
    val readingsCompleted: Int = 0,
    val wordsRead: Int = 0,
    val wordsSaved: Int = 0,
    val wordsReviewed: Int = 0,
    val assessments: Int = 0,
) {
    val minutes get() = seconds / 60

    // un día cuenta si se leyó, se terminó algo o se practicó; abrir una lectura sin leerla no basta
    val active get() = seconds > 0 || readingsCompleted > 0 || wordsSaved > 0 || wordsReviewed > 0 || assessments > 0

    operator fun plus(o: Totals) =
        Totals(
            seconds + o.seconds,
            readingsStarted + o.readingsStarted,
            readingsCompleted + o.readingsCompleted,
            wordsRead + o.wordsRead,
            wordsSaved + o.wordsSaved,
            wordsReviewed + o.wordsReviewed,
            assessments + o.assessments,
        )

    companion object {
        val ZERO = Totals()
    }
}

// la clave de una fila de daily_progress
data class DayKey(
    val day: LocalDate,
    val language: String,
)

// la proyección diaria de learning_activity (ADR-0011). El cálculo incremental (un evento suma su incremento a su día) y
// la reconstrucción (todos los eventos de nuevo) usan las mismas dos funciones, así que dan lo mismo
object Projection {
    fun day(
        activity: LearningActivity,
        zone: ZoneId,
    ): DayKey = DayKey(activity.at.atZone(zone).toLocalDate(), activity.language)

    fun increment(activity: LearningActivity): Totals =
        when (activity.type) {
            ActivityType.READING_STARTED -> Totals(readingsStarted = 1)
            ActivityType.PROGRESS -> Totals(seconds = activity.seconds)
            ActivityType.READING_COMPLETED -> Totals(readingsCompleted = 1, wordsRead = activity.words, seconds = activity.seconds)
            ActivityType.WORD_SAVED -> Totals(wordsSaved = 1)
            ActivityType.WORD_REVIEWED -> Totals(wordsReviewed = 1)
            ActivityType.ASSESSMENT_SUBMITTED -> Totals(assessments = 1)
        }

    fun rebuild(
        activities: List<LearningActivity>,
        zone: ZoneId,
    ): Map<DayKey, Totals> =
        activities
            .groupBy { day(it, zone) }
            .mapValues { (_, events) -> events.fold(Totals.ZERO) { sum, event -> sum + increment(event) } }
}
