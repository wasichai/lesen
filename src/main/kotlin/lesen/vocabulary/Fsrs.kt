package lesen.vocabulary

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

// FSRS-5 (Free Spaced Repetition Scheduler), ADR-0010: los parámetros por defecto publicados, retención deseada 0,9,
// pasos de aprendizaje de 1 y 10 minutos, de reaprendizaje de 10 minutos y sin aleatoriedad en los intervalos (el mismo
// repaso da siempre la misma fecha). Es la misma máquina que el planificador de referencia (py-fsrs 4.x), escrita de
// nuevo en Kotlin y comprobada en FsrsTest contra los valores de sus fórmulas
enum class CardState { LEARNING, REVIEW, RELEARNING }

data class Card(
    val state: CardState,
    // el paso de aprendizaje o reaprendizaje en que está (null en REVIEW)
    val step: Int?,
    // días que tarda la probabilidad de recordar en caer al 90 %; null hasta el primer repaso
    val stability: Double?,
    // de 1 a 10; null hasta el primer repaso
    val difficulty: Double?,
    val due: Instant,
    val lastReview: Instant?,
) {
    companion object {
        fun new(at: Instant) = Card(CardState.LEARNING, 0, null, null, at, null)
    }
}

// las cuatro respuestas de un repaso
enum class Rating(
    val grade: Int,
) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
}

object Fsrs {
    val W =
        doubleArrayOf(
            0.40255,
            1.18385,
            3.173,
            15.69105,
            7.1949,
            0.5345,
            1.4604,
            0.0046,
            1.54575,
            0.1192,
            1.01925,
            1.9395,
            0.11,
            0.29605,
            2.2698,
            0.2315,
            2.9898,
            0.51655,
            0.6621,
        )
    const val RETENTION = 0.9
    private const val DECAY = -0.5
    private val FACTOR = RETENTION.pow(1 / DECAY) - 1
    private const val MAX_INTERVAL_DAYS = 36500L
    private val LEARNING_STEPS = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10))
    private val RELEARNING_STEPS = listOf(Duration.ofMinutes(10))

    // probabilidad de recordar la tarjeta en un momento (días enteros desde el último repaso, como la referencia)
    fun retrievability(
        card: Card,
        at: Instant,
    ): Double {
        val last = card.lastReview ?: return 0.0
        val days = max(0L, daysBetween(last, at))
        return (1 + FACTOR * days / card.stability!!).pow(DECAY)
    }

    fun review(
        card: Card,
        rating: Rating,
        at: Instant,
    ): Card {
        val days = card.lastReview?.let { daysBetween(it, at) }
        val sameDay = days != null && days < 1
        val (stability, difficulty) =
            when {
                card.stability == null || card.difficulty == null -> initialStability(rating) to initialDifficulty(rating)
                sameDay -> shortTermStability(card.stability, rating) to nextDifficulty(card.difficulty, rating)
                else -> nextStability(card.difficulty, card.stability, retrievability(card, at), rating) to nextDifficulty(card.difficulty, rating)
            }
        var state = card.state
        var step = card.step
        val interval: Duration
        when (card.state) {
            CardState.LEARNING, CardState.RELEARNING -> {
                val steps = if (card.state == CardState.LEARNING) LEARNING_STEPS else RELEARNING_STEPS
                val current = card.step ?: 0
                if (current > steps.size) {
                    state = CardState.REVIEW
                    step = null
                    interval = days(stability)
                } else {
                    when (rating) {
                        Rating.AGAIN -> {
                            step = 0
                            interval = steps[0]
                        }
                        Rating.HARD -> {
                            interval =
                                when {
                                    current == 0 && steps.size == 1 -> steps[0].multipliedBy(3).dividedBy(2)
                                    current == 0 -> steps[0].plus(steps[1]).dividedBy(2)
                                    else -> steps[current]
                                }
                        }
                        Rating.GOOD -> {
                            if (current + 1 == steps.size) {
                                state = CardState.REVIEW
                                step = null
                                interval = days(stability)
                            } else {
                                step = current + 1
                                interval = steps[current + 1]
                            }
                        }
                        Rating.EASY -> {
                            state = CardState.REVIEW
                            step = null
                            interval = days(stability)
                        }
                    }
                }
            }
            CardState.REVIEW -> {
                if (rating == Rating.AGAIN) {
                    state = CardState.RELEARNING
                    step = 0
                    interval = RELEARNING_STEPS[0]
                } else {
                    interval = days(stability)
                }
            }
        }
        return Card(state, step, stability, difficulty, at.plus(interval), at)
    }

    // el intervalo en días enteros para la retención deseada: con 0,9, tantos días como la estabilidad
    fun intervalDays(stability: Double): Long {
        val days = (stability / FACTOR * (RETENTION.pow(1 / DECAY) - 1)).roundHalfEven()
        return min(max(days, 1L), MAX_INTERVAL_DAYS)
    }

    private fun days(stability: Double) = Duration.ofDays(intervalDays(stability))

    fun initialStability(rating: Rating) = max(W[rating.grade - 1], 0.1)

    fun initialDifficulty(rating: Rating) = (W[4] - exp(W[5] * (rating.grade - 1)) + 1).coerceIn(1.0, 10.0)

    fun shortTermStability(
        stability: Double,
        rating: Rating,
    ) = stability * exp(W[17] * (rating.grade - 3 + W[18]))

    fun nextDifficulty(
        difficulty: Double,
        rating: Rating,
    ): Double {
        val delta = -(W[6] * (rating.grade - 3))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        return (W[7] * initialDifficulty(Rating.EASY) + (1 - W[7]) * damped).coerceIn(1.0, 10.0)
    }

    fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating,
    ): Double =
        if (rating == Rating.AGAIN) {
            min(
                W[11] * difficulty.pow(-W[12]) * ((stability + 1).pow(W[13]) - 1) * exp((1 - retrievability) * W[14]),
                stability / exp(W[17] * W[18]),
            )
        } else {
            val hard = if (rating == Rating.HARD) W[15] else 1.0
            val easy = if (rating == Rating.EASY) W[16] else 1.0
            stability * (1 + exp(W[8]) * (11 - difficulty) * stability.pow(-W[9]) * (exp((1 - retrievability) * W[10]) - 1) * hard * easy)
        }

    // días completos entre dos momentos, redondeando hacia abajo (como timedelta.days)
    private fun daysBetween(
        from: Instant,
        to: Instant,
    ) = Math.floorDiv(Duration.between(from, to).seconds, 86_400L)

    // round() de Python: al par más cercano en los empates
    private fun Double.roundHalfEven(): Long = Math.rint(this).roundToLong()
}

// el estado del cuaderno a partir de la tarjeta (ADR-0010): solo el repaso lo mueve. Sin repasar, SAVED; en aprendizaje
// o con menos de una semana de estabilidad, LEARNING; desde 7 días, FAMILIAR; desde 60 días, MASTERED
object ReviewStatus {
    const val FAMILIAR_DAYS = 7.0
    const val MASTERED_DAYS = 60.0

    fun of(card: Card?): VocabularyStatus =
        when {
            card?.lastReview == null -> VocabularyStatus.SAVED
            card.state != CardState.REVIEW -> VocabularyStatus.LEARNING
            card.stability!! >= MASTERED_DAYS -> VocabularyStatus.MASTERED
            card.stability >= FAMILIAR_DAYS -> VocabularyStatus.FAMILIAR
            else -> VocabularyStatus.LEARNING
        }
}
