package lesen.progress

import lesen.assessment.AssessmentService
import lesen.catalog.LearnerSignals
import lesen.catalog.LearnerSignalsSource
import lesen.common.ActivityType
import lesen.common.Learner
import lesen.common.LearningActivity
import lesen.common.LearningActivityListener
import lesen.common.requireLength
import lesen.learner.LearnerProfile
import lesen.learner.ProfileRequest
import lesen.learner.ProfileService
import lesen.vocabulary.ReviewService
import lesen.vocabulary.VocabularyStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// cada cifra dice si es una medida (se contó) o una estimación (se infiere sobre lo que la persona sabe o leyó)
enum class MetricKind { MEASURED, ESTIMATED }

data class Metric(
    val value: Int,
    val kind: MetricKind,
) {
    companion object {
        fun measured(value: Int) = Metric(value, MetricKind.MEASURED)

        fun estimated(value: Int) = Metric(value, MetricKind.ESTIMATED)
    }
}

data class DayView(
    val date: LocalDate,
    val minutes: Int,
    val active: Boolean,
    val goalMet: Boolean,
)

// la meta diaria: minutos de hoy contra los del perfil. Nunca hay «meta fallada»: solo cuánto se lleva
data class DailyGoal(
    val dailyMinutes: Int,
    val todayMinutes: Metric,
    val met: Boolean,
)

data class ReadingProgress(
    val started: Metric,
    val completed: Metric,
    val minutes: Metric,
    // las palabras de las lecturas terminadas: no sabemos si se leyó cada una
    val wordsRead: Metric,
    val minutesThisWeek: Metric,
    val completedThisWeek: Metric,
)

data class WeekCount(
    val weekStart: LocalDate,
    val value: Int,
)

data class VocabularyProgress(
    val encountered: Metric,
    val saved: Metric,
    // aprendiendo y dominadas salen del modelo de memoria del repaso (FSRS): son estimaciones
    val learning: Metric,
    val mastered: Metric,
    val due: Metric,
    // palabras guardadas por semana, las últimas 8
    val growth: List<WeekCount>,
)

data class ComprehensionProgress(
    val answered: Metric,
    val average: Metric?,
    val byType: List<ScoreView>,
    val byLevel: List<ScoreView>,
)

// el nivel MCER siempre es una estimación: el que la persona declaró, y lo que su comprensión sugiere
data class LevelView(
    val level: String,
    val kind: MetricKind,
    val signal: LevelSignal,
    val answeredAtLevel: Int,
)

data class AchievementView(
    val code: String,
    val kind: AchievementKind,
    val threshold: Int,
    val value: Int,
    val earnedAt: Instant?,
)

data class Dashboard(
    val language: String,
    val timeZone: String,
    val today: LocalDate,
    val goal: DailyGoal,
    val streak: Streak,
    // los últimos 28 días, del más antiguo a hoy
    val days: List<DayView>,
    val reading: ReadingProgress,
    val vocabulary: VocabularyProgress,
    val comprehension: ComprehensionProgress,
    val level: LevelView,
    // los logros conseguidos, del más reciente al más antiguo (hasta 3)
    val recentAchievements: List<AchievementView>,
)

data class WeeklySummary(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val current: Boolean,
    val days: List<DayView>,
    val minutes: Metric,
    val readingsCompleted: Metric,
    val wordsRead: Metric,
    val wordsSaved: Metric,
    val wordsReviewed: Metric,
    val assessments: Metric,
    val activeDays: Int,
    val target: Int,
    val met: Boolean,
    val streak: Streak,
    val achievements: List<AchievementView>,
)

data class Goals(
    val dailyMinutes: Int,
    val weeklyDays: Int,
    val timeZone: String,
    val weeklySummary: Boolean,
    val configured: Boolean,
)

data class GoalsRequest(
    val dailyMinutes: Int? = null,
    val weeklyDays: Int? = null,
    val timeZone: String? = null,
    val weeklySummary: Boolean? = null,
)

// registra cada actividad una vez, la suma a su día (en la zona horaria de la persona) y concede los logros que tocan
@Component
class ProgressRecorder(
    private val store: ProgressStore,
    private val clock: Clock,
) : LearningActivityListener {
    override suspend fun record(activity: LearningActivity) {
        if (!store.insert(activity)) return
        val settings = store.settings(activity.organizationId, activity.userId)
        store.addDay(activity.organizationId, activity.userId, Projection.day(activity, zone(settings)), Projection.increment(activity))
        // el avance de lectura llega cada pocos segundos: sus logros se conceden con el siguiente evento o al mirar el panel
        if (activity.type != ActivityType.PROGRESS) award(activity.organizationId, activity.userId, settings)
    }

    suspend fun lifetime(
        o: UUID,
        u: UUID,
        settings: ProgressSettings,
    ): Lifetime {
        val perDay =
            store
                .days(o, u, null, null)
                .entries
                .groupBy({ it.key.day }, { it.value })
                .mapValues { (_, t) -> t.fold(Totals.ZERO, Totals::plus) }
        val active = perDay.filterValues { it.active }.keys
        val today = clock.instant().atZone(zone(settings)).toLocalDate()
        return Lifetime(perDay.values.fold(Totals.ZERO, Totals::plus), active.size, Streaks.weekly(active, settings.weeklyDays, today))
    }

    suspend fun award(
        o: UUID,
        u: UUID,
        settings: ProgressSettings,
    ) {
        val earned = Achievements.earned(store.rules(), lifetime(o, u, settings))
        store.grant(o, u, earned, clock.instant())
    }

    companion object {
        fun zone(settings: ProgressSettings): ZoneId = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
    }
}

@Service
class ProgressService(
    private val store: ProgressStore,
    private val recorder: ProgressRecorder,
    private val profiles: ProfileService,
    private val reviews: ReviewService,
    private val assessments: AssessmentService,
    private val tx: TransactionalOperator,
    private val clock: Clock,
) {
    suspend fun dashboard(
        me: Learner,
        language: String?,
    ): Dashboard {
        requireLength(language, "language", 8)
        val profile = profile(me)
        val study = language ?: profile.targetLanguage
        val settings = store.settings(me.organizationId, me.userId)
        recorder.award(me.organizationId, me.userId, settings)
        val zone = ProgressRecorder.zone(settings)
        val today = clock.instant().atZone(zone).toLocalDate()
        val lifetime = recorder.lifetime(me.organizationId, me.userId, settings)
        val all = store.days(me.organizationId, me.userId, null, null)
        val perDay = all.entries.groupBy({ it.key.day }, { it.value }).mapValues { (_, t) -> t.fold(Totals.ZERO, Totals::plus) }
        val inLanguage = all.filterKeys { it.language == study }
        val languageTotal = inLanguage.values.fold(Totals.ZERO, Totals::plus)
        val weekStart = Streaks.weekStart(today)
        val thisWeek = inLanguage.filterKeys { !it.day.isBefore(weekStart) }.values.fold(Totals.ZERO, Totals::plus)
        val vocabulary = reviews.stats(me, study)
        val counts = assessments.comprehension(me.organizationId, me.userId, study)
        val todayMinutes = perDay[today]?.minutes ?: 0
        val growthFrom = weekStart.minusWeeks(7)
        val saved = inLanguage.filterKeys { !it.day.isBefore(growthFrom) }.entries.groupBy({ Streaks.weekStart(it.key.day) }, { it.value.wordsSaved })
        return Dashboard(
            language = study,
            timeZone = zone.id,
            today = today,
            goal = DailyGoal(profile.dailyMinutes, Metric.measured(todayMinutes), todayMinutes >= profile.dailyMinutes),
            streak = lifetime.streak,
            days = (27 downTo 0).map { today.minusDays(it.toLong()) }.map { day(it, perDay[it], profile.dailyMinutes) },
            reading =
                ReadingProgress(
                    started = Metric.measured(languageTotal.readingsStarted),
                    completed = Metric.measured(languageTotal.readingsCompleted),
                    minutes = Metric.measured(languageTotal.minutes),
                    wordsRead = Metric.estimated(languageTotal.wordsRead),
                    minutesThisWeek = Metric.measured(thisWeek.minutes),
                    completedThisWeek = Metric.measured(thisWeek.readingsCompleted),
                ),
            vocabulary =
                VocabularyProgress(
                    encountered = Metric.measured(vocabulary.encountered),
                    saved = Metric.measured(vocabulary.total),
                    learning = Metric.estimated((vocabulary.byStatus[VocabularyStatus.LEARNING] ?: 0) + (vocabulary.byStatus[VocabularyStatus.FAMILIAR] ?: 0)),
                    mastered = Metric.estimated(vocabulary.byStatus[VocabularyStatus.MASTERED] ?: 0),
                    due = Metric.measured(vocabulary.dueNow),
                    growth = (0..7).map { growthFrom.plusWeeks(it.toLong()) }.map { WeekCount(it, saved[it].orEmpty().sum()) },
                ),
            comprehension =
                ComprehensionProgress(
                    answered = Metric.measured(counts.sumOf { it.answered }),
                    average = Comprehension.score(counts)?.let(Metric::measured),
                    byType = Comprehension.by(counts) { it.type.name },
                    byLevel = Comprehension.by(counts) { it.level },
                ),
            level =
                LevelView(
                    profile.level,
                    MetricKind.ESTIMATED,
                    Comprehension.signal(counts, profile.level),
                    counts.filter { it.level == profile.level }.sumOf { it.answered },
                ),
            recentAchievements = achievements(me, lifetime).filter { it.earnedAt != null }.sortedByDescending { it.earnedAt }.take(3),
        )
    }

    // la semana (de lunes a domingo, en la zona de la persona) que contiene el día pedido; por defecto, la en curso
    suspend fun weekly(
        me: Learner,
        week: LocalDate?,
    ): WeeklySummary {
        val profile = profile(me)
        val settings = store.settings(me.organizationId, me.userId)
        val zone = ProgressRecorder.zone(settings)
        val today = clock.instant().atZone(zone).toLocalDate()
        val start = Streaks.weekStart(week ?: today)
        val end = start.plusDays(6)
        val rows = store.days(me.organizationId, me.userId, start, end)
        val perDay = rows.entries.groupBy({ it.key.day }, { it.value }).mapValues { (_, t) -> t.fold(Totals.ZERO, Totals::plus) }
        val sum = perDay.values.fold(Totals.ZERO, Totals::plus)
        val lifetime = recorder.lifetime(me.organizationId, me.userId, settings)
        val activeDays = perDay.count { (day, t) -> t.active && !day.isAfter(today) }
        val endOfWeek = end.plusDays(1).atStartOfDay(zone).toInstant()
        return WeeklySummary(
            weekStart = start,
            weekEnd = end,
            current = start == Streaks.weekStart(today),
            days = (0..6).map { start.plusDays(it.toLong()) }.map { day(it, perDay[it], profile.dailyMinutes) },
            minutes = Metric.measured(sum.minutes),
            readingsCompleted = Metric.measured(sum.readingsCompleted),
            wordsRead = Metric.estimated(sum.wordsRead),
            wordsSaved = Metric.measured(sum.wordsSaved),
            wordsReviewed = Metric.measured(sum.wordsReviewed),
            assessments = Metric.measured(sum.assessments),
            activeDays = activeDays,
            target = settings.weeklyDays,
            met = activeDays >= settings.weeklyDays,
            streak = lifetime.streak,
            achievements =
                achievements(me, lifetime).filter {
                    it.earnedAt != null && !it.earnedAt.isBefore(start.atStartOfDay(zone).toInstant()) && it.earnedAt.isBefore(endOfWeek)
                },
        )
    }

    suspend fun achievements(me: Learner): List<AchievementView> {
        val settings = store.settings(me.organizationId, me.userId)
        recorder.award(me.organizationId, me.userId, settings)
        return achievements(me, recorder.lifetime(me.organizationId, me.userId, settings))
    }

    suspend fun goals(me: Learner): Goals {
        val settings = store.settings(me.organizationId, me.userId)
        return Goals(profile(me).dailyMinutes, settings.weeklyDays, settings.timeZone, settings.weeklySummary, settings.configured)
    }

    // cambiar de zona horaria reconstruye la proyección: los mismos eventos caen en otros días
    suspend fun saveGoals(
        me: Learner,
        request: GoalsRequest,
    ): Goals {
        val profile = profile(me)
        val current = store.settings(me.organizationId, me.userId)
        requireLength(request.timeZone, "timeZone", 64)
        val zone =
            request.timeZone?.let {
                try {
                    ZoneId.of(it).id
                } catch (_: DateTimeException) {
                    throw ValidationException("Invalid time zone", "timeZone", "must be an IANA time zone such as Europe/Berlin")
                }
            } ?: current.timeZone
        val weeklyDays = request.weeklyDays ?: current.weeklyDays
        if (weeklyDays !in 1..7) throw ValidationException("Invalid weekly days", "weeklyDays", "must be between 1 and 7")
        if (request.dailyMinutes != null && request.dailyMinutes != profile.dailyMinutes) {
            profiles.save(me.organizationId, me.userId, profile.request(request.dailyMinutes))
        }
        val next = ProgressSettings(weeklyDays, zone, request.weeklySummary ?: current.weeklySummary, true)
        store.saveSettings(me.organizationId, me.userId, next)
        if (zone != current.timeZone) rebuild(me.organizationId, me.userId, ZoneId.of(zone))
        return goals(me)
    }

    // la proyección diaria, de nuevo desde todos los eventos (ADR-0011)
    suspend fun rebuild(
        o: UUID,
        u: UUID,
        zone: ZoneId,
    ) {
        tx.executeAndAwait {
            val days = Projection.rebuild(store.activities(o, u), zone)
            store.clearDays(o, u)
            days.forEach { (key, totals) -> store.addDay(o, u, key, totals) }
        }
    }

    private suspend fun achievements(
        me: Learner,
        lifetime: Lifetime,
    ): List<AchievementView> {
        val earned = store.earned(me.organizationId, me.userId)
        return store.rules().map { AchievementView(it.code, it.kind, it.threshold, minOf(lifetime.value(it.kind), it.threshold), earned[it.code]) }
    }

    private suspend fun profile(me: Learner): LearnerProfile = profiles.find(me.organizationId, me.userId) ?: throw NotFoundException("No learner profile yet")

    private fun day(
        date: LocalDate,
        totals: Totals?,
        dailyMinutes: Int,
    ) = DayView(date, totals?.minutes ?: 0, totals?.active == true, (totals?.minutes ?: 0) >= dailyMinutes)

    private fun LearnerProfile.request(dailyMinutes: Int) =
        ProfileRequest(interfaceLanguage, targetLanguage, level, levelEstimated, goals, topics, dailyMinutes, difficulty)
}

// las señales del historial para la recomendación: la banda de nivel según la comprensión y lo que se lee esta semana
@Component
class ProgressSignals(
    private val store: ProgressStore,
    private val assessments: AssessmentService,
    private val clock: Clock,
) : LearnerSignalsSource {
    override suspend fun signals(
        organizationId: UUID,
        userId: UUID,
        profile: LearnerProfile,
    ): LearnerSignals {
        val counts = assessments.comprehension(organizationId, userId, profile.targetLanguage)
        return LearnerSignals(
            Comprehension.shift(Comprehension.signal(counts, profile.level)),
            store.trending(organizationId, profile.targetLanguage, clock.instant().minus(TRENDING_WINDOW)),
        )
    }

    companion object {
        val TRENDING_WINDOW: Duration = Duration.ofDays(7)
    }
}
