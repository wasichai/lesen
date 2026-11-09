package lesen.progress

import lesen.assessment.ComprehensionCount
import lesen.assessment.QuestionType
import lesen.common.ActivityType
import lesen.common.LearningActivity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// las reglas puras del progreso: la proyección por día, la racha semanal, los logros y la comprensión
class ProgressRulesTest {
    private val o = UUID.randomUUID()
    private val u = UUID.randomUUID()

    private fun event(
        type: ActivityType,
        at: String,
        seconds: Int = 0,
        words: Int = 0,
        language: String = "de",
    ) = LearningActivity(o, u, type, Instant.parse(at), language, null, null, seconds, words)

    // 2026-11-09 es lunes
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    @Test
    fun `an event falls on the day of the person's time zone`() {
        val late = event(ActivityType.PROGRESS, "2026-11-09T23:30:00Z", seconds = 120)
        assertThat(Projection.day(late, ZoneId.of("UTC")).day).isEqualTo(d(9))
        assertThat(Projection.day(late, ZoneId.of("Europe/Berlin")).day).isEqualTo(d(10))
        assertThat(Projection.day(late, ZoneId.of("America/Lima")).day).isEqualTo(d(9))
        val early = event(ActivityType.PROGRESS, "2026-11-10T03:00:00Z", seconds = 60)
        assertThat(Projection.day(early, ZoneId.of("America/Lima")).day).isEqualTo(d(9))
    }

    @Test
    fun `rebuilding gives the same days as adding one event at a time`() {
        val events =
            listOf(
                event(ActivityType.READING_STARTED, "2026-11-09T08:00:00Z"),
                event(ActivityType.PROGRESS, "2026-11-09T08:05:00Z", seconds = 300),
                event(ActivityType.READING_COMPLETED, "2026-11-09T23:10:00Z", words = 420),
                event(ActivityType.WORD_SAVED, "2026-11-09T23:20:00Z"),
                event(ActivityType.WORD_SAVED, "2026-11-10T07:00:00Z", language = "en"),
                event(ActivityType.WORD_REVIEWED, "2026-11-11T12:00:00Z"),
                event(ActivityType.ASSESSMENT_SUBMITTED, "2026-11-11T12:30:00Z"),
            )
        for (zone in listOf("UTC", "Europe/Berlin", "Pacific/Kiritimati", "America/Lima").map(ZoneId::of)) {
            val incremental = mutableMapOf<DayKey, Totals>()
            events.forEach { e -> incremental.merge(Projection.day(e, zone), Projection.increment(e), Totals::plus) }
            assertThat(Projection.rebuild(events, zone)).isEqualTo(incremental)
            assertThat(Projection.rebuild(events.shuffled(), zone)).isEqualTo(incremental)
        }
        val berlin = Projection.rebuild(events, ZoneId.of("Europe/Berlin"))
        assertThat(berlin.getValue(DayKey(d(10), "de"))).isEqualTo(Totals(readingsCompleted = 1, wordsRead = 420, wordsSaved = 1))
        assertThat(berlin.getValue(DayKey(d(9), "de")).minutes).isEqualTo(5)
    }

    @Test
    fun `opening a reading without reading it is not an active day`() {
        assertThat(Totals(readingsStarted = 1).active).isFalse()
        assertThat(Totals(seconds = 1).active).isTrue()
        assertThat(Totals(wordsReviewed = 1).active).isTrue()
    }

    @Test
    fun `rest days never break the weekly streak`() {
        // tres semanas cumplidas con 3 días cada una, y descanso el resto
        val days = setOf(d(2), d(4), d(6), d(9), d(11), d(13), d(16), d(17), d(18))
        val streak = Streaks.weekly(days, 3, d(23))
        assertThat(streak.current).isEqualTo(3)
        assertThat(streak.best).isEqualTo(3)
        assertThat(streak.weeksMet).isEqualTo(3)
        assertThat(streak.activeThisWeek).isEqualTo(0)
        assertThat(streak.metThisWeek).isFalse()
    }

    @Test
    fun `the week in progress only adds, it never takes away`() {
        val lastWeeks = setOf(d(9), d(10), d(16), d(17))
        val monday = Streaks.weekly(lastWeeks, 2, d(23))
        assertThat(monday.current).isEqualTo(2)
        val done = Streaks.weekly(lastWeeks + setOf(d(23), d(24)), 2, d(24))
        assertThat(done.current).isEqualTo(3)
        assertThat(done.metThisWeek).isTrue()
        // la misma racha el domingo sin leer esta semana: sigue siendo 2
        assertThat(Streaks.weekly(lastWeeks, 2, d(29)).current).isEqualTo(2)
    }

    @Test
    fun `a missed week starts a new run but keeps the best and the weeks met`() {
        val days = setOf(d(2), d(3), d(9), d(10), d(23), d(24))
        val streak = Streaks.weekly(days, 2, d(25))
        assertThat(streak.current).isEqualTo(1)
        assertThat(streak.best).isEqualTo(2)
        assertThat(streak.weeksMet).isEqualTo(3)
    }

    @Test
    fun `weeks start on monday and future days do not count`() {
        assertThat(Streaks.weekStart(d(15))).isEqualTo(d(9))
        assertThat(Streaks.weekStart(d(9))).isEqualTo(d(9))
        assertThat(Streaks.weekly(setOf(d(10), d(14)), 2, d(11)).activeThisWeek).isEqualTo(1)
    }

    @Test
    fun `achievements follow their rules deterministically`() {
        val rules =
            listOf(
                AchievementRule("FIRST_READING", AchievementKind.READINGS_COMPLETED, 1, 1),
                AchievementRule("FIRST_HOUR", AchievementKind.MINUTES, 60, 2),
                AchievementRule("FIRST_WEEK", AchievementKind.WEEKS_MET, 1, 3),
                AchievementRule("DAYS_10", AchievementKind.ACTIVE_DAYS, 10, 4),
            )
        val streak = Streak(0, 0, 0, 3, 0, false)
        assertThat(Achievements.earned(rules, Lifetime(Totals.ZERO, 0, streak))).isEmpty()
        val some = Lifetime(Totals(seconds = 3599, readingsCompleted = 1), 2, streak.copy(weeksMet = 1))
        assertThat(Achievements.earned(rules, some)).containsExactly("FIRST_READING", "FIRST_WEEK")
        assertThat(Achievements.earned(rules, some.copy(totals = some.totals.copy(seconds = 3600)))).contains("FIRST_HOUR")
    }

    @Test
    fun `comprehension counts partial as half and needs enough answers to suggest a level`() {
        val counts =
            listOf(
                ComprehensionCount(QuestionType.MULTIPLE_CHOICE, "A2", 6, 0, 0),
                ComprehensionCount(QuestionType.TRUE_FALSE, "A2", 2, 2, 0),
                ComprehensionCount(QuestionType.TRUE_FALSE, "B1", 0, 0, 2),
            )
        assertThat(Comprehension.score(counts)).isEqualTo(75)
        assertThat(Comprehension.score(emptyList())).isNull()
        assertThat(Comprehension.by(counts) { it.level }.map { it.key to it.score }).containsExactly("A2" to 90, "B1" to 0)
        assertThat(Comprehension.signal(counts, "A2")).isEqualTo(LevelSignal.UP)
        assertThat(Comprehension.signal(counts, "B1")).isEqualTo(LevelSignal.STEADY)
        val weak = listOf(ComprehensionCount(QuestionType.MATCHING, "B1", 2, 0, 6))
        assertThat(Comprehension.signal(weak, "B1")).isEqualTo(LevelSignal.DOWN)
        assertThat(Comprehension.shift(LevelSignal.DOWN)).isEqualTo(-1)
    }
}
