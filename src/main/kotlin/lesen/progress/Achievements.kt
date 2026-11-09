package lesen.progress

enum class AchievementKind { READINGS_COMPLETED, MINUTES, WORDS_SAVED, WORDS_REVIEWED, ASSESSMENTS, ACTIVE_DAYS, WEEKS_MET, BEST_STREAK }

// un logro es un dato (lesen.achievement): qué se cuenta y desde cuánto. Las reglas son deterministas: dado lo hecho,
// siempre los mismos logros. Se conceden una vez y no se retiran, aunque después se borre una palabra
data class AchievementRule(
    val code: String,
    val kind: AchievementKind,
    val threshold: Int,
    val position: Int,
)

// lo que la persona lleva hecho en toda su historia
data class Lifetime(
    val totals: Totals,
    val activeDays: Int,
    val streak: Streak,
) {
    fun value(kind: AchievementKind): Int =
        when (kind) {
            AchievementKind.READINGS_COMPLETED -> totals.readingsCompleted
            AchievementKind.MINUTES -> totals.minutes
            AchievementKind.WORDS_SAVED -> totals.wordsSaved
            AchievementKind.WORDS_REVIEWED -> totals.wordsReviewed
            AchievementKind.ASSESSMENTS -> totals.assessments
            AchievementKind.ACTIVE_DAYS -> activeDays
            AchievementKind.WEEKS_MET -> streak.weeksMet
            AchievementKind.BEST_STREAK -> streak.best
        }
}

object Achievements {
    fun earned(
        rules: List<AchievementRule>,
        lifetime: Lifetime,
    ): List<String> = rules.filter { lifetime.value(it.kind) >= it.threshold }.map { it.code }
}
