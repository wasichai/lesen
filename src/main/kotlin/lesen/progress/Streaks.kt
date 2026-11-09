package lesen.progress

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

// la racha semanal (ADR-0011): una semana se cumple con los días de lectura que la persona eligió; los demás son de
// descanso y no cuentan en contra. La semana en curso nunca rompe la racha: si todavía no se cumplió, la racha sigue
// siendo la de las semanas anteriores
data class Streak(
    // semanas cumplidas seguidas hasta ahora (la en curso suma si ya se cumplió)
    val current: Int,
    // la racha más larga
    val best: Int,
    // semanas cumplidas en total
    val weeksMet: Int,
    val target: Int,
    // días con actividad en la semana en curso
    val activeThisWeek: Int,
    val metThisWeek: Boolean,
)

object Streaks {
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekly(
        activeDays: Set<LocalDate>,
        target: Int,
        today: LocalDate,
    ): Streak {
        val need = target.coerceIn(1, 7)
        val thisWeek = weekStart(today)
        val perWeek = activeDays.filter { !it.isAfter(today) }.groupingBy { weekStart(it) }.eachCount()
        val met = perWeek.filterValues { it >= need }.keys
        val activeThisWeek = perWeek[thisWeek] ?: 0
        // hacia atrás desde la semana pasada; la en curso solo suma, nunca resta
        var current = 0
        var week = thisWeek.minusWeeks(1)
        while (week in met) {
            current++
            week = week.minusWeeks(1)
        }
        if (thisWeek in met) current++
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (w in met.sorted()) {
            run = if (previous != null && previous.plusWeeks(1) == w) run + 1 else 1
            best = maxOf(best, run)
            previous = w
        }
        return Streak(current, maxOf(best, current), met.size, need, activeThisWeek, thisWeek in met)
    }
}
