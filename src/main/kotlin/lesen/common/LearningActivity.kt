package lesen.common

import java.time.Instant
import java.util.UUID

enum class ActivityType { READING_STARTED, PROGRESS, READING_COMPLETED, WORD_SAVED, WORD_REVIEWED, ASSESSMENT_SUBMITTED }

// algo que hizo una persona y que cuenta para su progreso (ADR-0011). key hace idempotente lo que puede repetirse; seconds
// son los segundos de lectura nuevos (no el total) y words, las palabras de una lectura terminada
data class LearningActivity(
    val organizationId: UUID,
    val userId: UUID,
    val type: ActivityType,
    val at: Instant,
    val language: String,
    val key: String? = null,
    val readingId: UUID? = null,
    val seconds: Int = 0,
    val words: Int = 0,
)

// quien emite (library, vocabulary, assessment) solo conoce esta interfaz; progress la implementa. Así ninguna capacidad
// que emite depende de la que cuenta, igual que ReadingCompletionListener
interface LearningActivityListener {
    suspend fun record(activity: LearningActivity)
}

suspend fun List<LearningActivityListener>.emit(activity: LearningActivity) = forEach { it.record(activity) }
