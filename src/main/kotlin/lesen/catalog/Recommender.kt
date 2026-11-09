package lesen.catalog

import lesen.learner.Difficulty
import lesen.learner.LearnerProfile
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

// lo que la recomendación aprende del historial (ADR-0011): cuánto mover la banda de nivel por la comprensión medida
// (-1, 0 o +1) y cuántas personas de la organización terminaron cada lectura en los últimos 7 días (agregado anónimo)
data class LearnerSignals(
    val bandShift: Int = 0,
    val trending: Map<String, Int> = emptyMap(),
) {
    companion object {
        val NONE = LearnerSignals()
    }
}

// la fuente de esas señales vive en progress, que depende de catalog; catalog solo conoce esta interfaz
interface LearnerSignalsSource {
    suspend fun signals(
        organizationId: UUID,
        userId: UUID,
        profile: LearnerProfile,
    ): LearnerSignals
}

// recomendación v1 (docs/architecture/domain-model.md §Recomendación): una regla determinista y explicable, no un
// modelo. Puntúa las lecturas del idioma que la persona estudia por cercanía a su banda de nivel (ajustada por su
// comprensión), por los temas que eligió y los de lo que ya terminó, por novedad y por lo que se lee esta semana; deja
// fuera lo terminado. Cada recomendación dice por qué
object Recommender {
    private val ORDER = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val NEW_FOR = Duration.ofDays(21)

    // los niveles que encajan con la persona: el suyo y, según la dificultad que prefiere, el de abajo o el de arriba.
    // shift mueve la banda entera cuando la comprensión medida dice que el nivel se queda corto o largo
    fun band(
        profile: LearnerProfile,
        shift: Int = 0,
    ): Set<String> {
        val i = (ORDER.indexOf(profile.level).coerceAtLeast(0) + shift.coerceIn(-1, 1)).coerceIn(0, ORDER.lastIndex)
        val extra =
            when (profile.difficulty) {
                Difficulty.COMFORTABLE -> i - 1
                Difficulty.BALANCED -> i
                Difficulty.CHALLENGING -> i + 1
            }.coerceIn(0, ORDER.lastIndex)
        return setOf(ORDER[i], ORDER[extra])
    }

    fun recommend(
        profile: LearnerProfile,
        candidates: List<ReadingCard>,
        now: Instant,
        signals: LearnerSignals = LearnerSignals.NONE,
        limit: Int = 6,
    ): List<Recommendation> {
        val declared = band(profile)
        val band = band(profile, signals.bandShift)
        val bandIndexes = band.map { ORDER.indexOf(it) }
        // los temas principales de lo que la persona ya terminó en este idioma
        val finished =
            candidates
                .filter { it.language == profile.targetLanguage && it.progress?.completed == true }
                .flatMap { card -> card.topics.map { it.substringBefore('/') } }
                .toSet()
        return candidates
            .asSequence()
            .filter { it.language == profile.targetLanguage && it.progress?.completed != true }
            .map { card ->
                val reasons = mutableListOf<String>()
                val distance = bandIndexes.minOf { abs(ORDER.indexOf(card.level) - it) }
                var score = 10.0 - 4.0 * distance
                if (distance == 0) reasons += if (card.level in declared) "LEVEL" else "COMPREHENSION"
                val topicMatch = card.topics.any { t -> profile.topics.any { p -> t == p || t.startsWith("$p/") || p.startsWith("$t/") } }
                if (topicMatch) {
                    score += 3.0
                    reasons += "TOPIC"
                }
                if (!topicMatch && card.topics.any { it.substringBefore('/') in finished }) {
                    score += 2.0
                    reasons += "HISTORY"
                }
                if (Duration.between(card.publishedAt, now) < NEW_FOR) {
                    score += 1.0
                    reasons += "NEW"
                }
                val readers = signals.trending[card.slug] ?: 0
                if (readers > 0) {
                    score += 0.5 + 0.25 * readers.coerceAtMost(4)
                    reasons += "TRENDING"
                }
                if (card.progress != null) score += 0.5
                Triple(card, score, reasons)
            }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<ReadingCard, Double, List<String>>> { it.second }.thenBy { it.first.title })
            .take(limit)
            .map { Recommendation(it.first, it.third) }
            .toList()
    }
}
