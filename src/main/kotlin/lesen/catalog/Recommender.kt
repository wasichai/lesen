package lesen.catalog

import lesen.learner.Difficulty
import lesen.learner.LearnerProfile
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

// recomendación v0 (docs/architecture/domain-model.md §Recomendación): una regla determinista y explicable, no un
// modelo. Puntúa las lecturas del idioma que la persona estudia por cercanía a su banda de nivel, por temas que eligió
// y por novedad; deja fuera lo terminado. Cada recomendación dice por qué
object Recommender {
    private val ORDER = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val NEW_FOR = Duration.ofDays(21)

    // los niveles que encajan con la persona: el suyo y, según la dificultad que prefiere, el de abajo o el de arriba
    fun band(profile: LearnerProfile): Set<String> {
        val i = ORDER.indexOf(profile.level).coerceAtLeast(0)
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
        limit: Int = 6,
    ): List<Recommendation> {
        val band = band(profile)
        val bandIndexes = band.map { ORDER.indexOf(it) }
        return candidates
            .asSequence()
            .filter { it.language == profile.targetLanguage && it.progress?.completed != true }
            .map { card ->
                val reasons = mutableListOf<String>()
                val distance = bandIndexes.minOf { abs(ORDER.indexOf(card.level) - it) }
                var score = 10.0 - 4.0 * distance
                if (distance == 0) reasons += "LEVEL"
                val topicMatch = card.topics.any { t -> profile.topics.any { p -> t == p || t.startsWith("$p/") || p.startsWith("$t/") } }
                if (topicMatch) {
                    score += 3.0
                    reasons += "TOPIC"
                }
                if (Duration.between(card.publishedAt, now) < NEW_FOR) {
                    score += 1.0
                    reasons += "NEW"
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
