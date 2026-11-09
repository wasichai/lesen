package lesen.progress

import lesen.assessment.ComprehensionCount
import kotlin.math.roundToInt

data class ScoreView(
    val key: String,
    val answered: Int,
    // de 0 a 100: lo correcto cuenta entero y lo parcial, la mitad
    val score: Int,
)

enum class LevelSignal { UP, STEADY, DOWN }

// la comprensión medida en las preguntas: la media y su reparto por tipo de pregunta y por nivel. También dice si el
// nivel declarado parece corto o largo, que es lo que mueve la banda de la recomendación
object Comprehension {
    const val MIN_ANSWERS = 8
    const val UP_FROM = 85
    const val DOWN_BELOW = 50

    fun score(counts: List<ComprehensionCount>): Int? {
        val answered = counts.sumOf { it.answered }
        if (answered == 0) return null
        return ((counts.sumOf { it.correct } + 0.5 * counts.sumOf { it.partial }) * 100 / answered).roundToInt()
    }

    fun by(
        counts: List<ComprehensionCount>,
        key: (ComprehensionCount) -> String,
    ): List<ScoreView> =
        counts
            .groupBy(key)
            .map { (k, group) -> ScoreView(k, group.sumOf { it.answered }, score(group)!!) }
            .sortedBy { it.key }

    // con pocas respuestas en su nivel no se dice nada: STEADY
    fun signal(
        counts: List<ComprehensionCount>,
        level: String,
    ): LevelSignal {
        val atLevel = counts.filter { it.level == level }
        if (atLevel.sumOf { it.answered } < MIN_ANSWERS) return LevelSignal.STEADY
        val score = score(atLevel)!!
        return when {
            score >= UP_FROM -> LevelSignal.UP
            score < DOWN_BELOW -> LevelSignal.DOWN
            else -> LevelSignal.STEADY
        }
    }

    fun shift(signal: LevelSignal) =
        when (signal) {
            LevelSignal.UP -> 1
            LevelSignal.STEADY -> 0
            LevelSignal.DOWN -> -1
        }
}
