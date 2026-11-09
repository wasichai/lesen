package lesen.vocabulary

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

// los valores esperados salen de las fórmulas publicadas de FSRS-5 con sus 19 parámetros por defecto, calculados aparte
// (D0(G) = w4 − e^(w5·(G−1)) + 1; S0(G) = w(G−1); R(t, S) = (1 + 19/81 · t/S)^−0,5…), no del código que se prueba
class FsrsTest {
    private val t0 = Instant.parse("2026-01-01T09:00:00Z")
    private val eps = within(1e-6)

    @Test
    fun `initial difficulty and stability follow the default parameters`() {
        assertThat(Fsrs.initialDifficulty(Rating.AGAIN)).isCloseTo(7.1949, eps)
        assertThat(Fsrs.initialDifficulty(Rating.HARD)).isCloseTo(6.488305268, eps)
        assertThat(Fsrs.initialDifficulty(Rating.GOOD)).isCloseTo(5.282434422, eps)
        assertThat(Fsrs.initialDifficulty(Rating.EASY)).isCloseTo(3.224501589, eps)
        assertThat(Rating.entries.map { Fsrs.initialStability(it) }).containsExactly(0.40255, 1.18385, 3.173, 15.69105)
    }

    @Test
    fun `with retention 0,9 the interval is the stability in whole days`() {
        assertThat(Fsrs.intervalDays(4.466858)).isEqualTo(4)
        assertThat(Fsrs.intervalDays(14.217284)).isEqualTo(14)
        assertThat(Fsrs.intervalDays(0.2)).isEqualTo(1)
        assertThat(Fsrs.intervalDays(1e9)).isEqualTo(36500)
    }

    @Test
    fun `a new word answered good walks the learning steps and then grows by review`() {
        val first = Fsrs.review(Card.new(t0), Rating.GOOD, t0)
        assertThat(first.state).isEqualTo(CardState.LEARNING)
        assertThat(first.step).isEqualTo(1)
        assertThat(first.due).isEqualTo(t0.plus(Duration.ofMinutes(10)))
        assertThat(first.stability!!).isCloseTo(3.173, eps)
        assertThat(first.difficulty!!).isCloseTo(5.282434422, eps)

        // diez minutos después, el mismo día: estabilidad a corto plazo y pasa a repaso
        val t1 = first.due
        val second = Fsrs.review(first, Rating.GOOD, t1)
        assertThat(second.state).isEqualTo(CardState.REVIEW)
        assertThat(second.step).isNull()
        assertThat(second.stability!!).isCloseTo(4.466858064, eps)
        assertThat(second.difficulty!!).isCloseTo(5.272967931, eps)
        assertThat(second.due).isEqualTo(t1.plus(Duration.ofDays(4)))

        // a su fecha (4 días): R = 0,909071, la estabilidad crece
        assertThat(Fsrs.retrievability(second, second.due)).isCloseTo(0.909071448, eps)
        val third = Fsrs.review(second, Rating.GOOD, second.due)
        assertThat(third.stability!!).isCloseTo(14.217284109, eps)
        assertThat(third.difficulty!!).isCloseTo(5.263544986, eps)
        assertThat(third.due).isEqualTo(second.due.plus(Duration.ofDays(14)))
        assertThat(ReviewStatus.of(third)).isEqualTo(VocabularyStatus.FAMILIAR)

        // olvidarla a su fecha: reaprendizaje en 10 minutos con la estabilidad de olvido
        val lapse = Fsrs.review(third, Rating.AGAIN, third.due)
        assertThat(lapse.state).isEqualTo(CardState.RELEARNING)
        assertThat(lapse.step).isEqualTo(0)
        assertThat(lapse.stability!!).isCloseTo(2.504170139, eps)
        assertThat(lapse.due).isEqualTo(third.due.plus(Duration.ofMinutes(10)))
        assertThat(ReviewStatus.of(lapse)).isEqualTo(VocabularyStatus.LEARNING)
    }

    @Test
    fun `easy skips the learning steps`() {
        val card = Fsrs.review(Card.new(t0), Rating.EASY, t0)
        assertThat(card.state).isEqualTo(CardState.REVIEW)
        assertThat(card.stability!!).isCloseTo(15.69105, eps)
        assertThat(card.difficulty!!).isCloseTo(3.224501589, eps)
        assertThat(card.due).isEqualTo(t0.plus(Duration.ofDays(16)))
    }

    @Test
    fun `again and hard keep the card in learning with short steps`() {
        val again = Fsrs.review(Card.new(t0), Rating.AGAIN, t0)
        assertThat(again.step).isEqualTo(0)
        assertThat(again.due).isEqualTo(t0.plus(Duration.ofMinutes(1)))
        val hard = Fsrs.review(again, Rating.HARD, again.due)
        assertThat(hard.step).isEqualTo(0)
        assertThat(hard.due).isEqualTo(again.due.plus(Duration.ofSeconds(330)))
        assertThat(ReviewStatus.of(hard)).isEqualTo(VocabularyStatus.LEARNING)
    }

    @Test
    fun `the same review gives the same card and better answers never give earlier dates`() {
        val learned = Fsrs.review(Fsrs.review(Card.new(t0), Rating.GOOD, t0), Rating.GOOD, t0.plusSeconds(600))
        val at = learned.due.plus(Duration.ofDays(3))
        assertThat(Fsrs.review(learned, Rating.GOOD, at)).isEqualTo(Fsrs.review(learned, Rating.GOOD, at))
        val dues = Rating.entries.map { Fsrs.review(learned, it, at).due }
        assertThat(dues).isSorted()
        // repasar tarde, con la memoria más débil, da una estabilidad mayor si se recuerda
        val onTime = Fsrs.review(learned, Rating.GOOD, learned.due).stability!!
        assertThat(Fsrs.review(learned, Rating.GOOD, at).stability!!).isGreaterThan(onTime)
    }

    @Test
    fun `status moves only with stability`() {
        assertThat(ReviewStatus.of(null)).isEqualTo(VocabularyStatus.SAVED)
        assertThat(ReviewStatus.of(Card.new(t0))).isEqualTo(VocabularyStatus.SAVED)
        val review = Card(CardState.REVIEW, null, 6.9, 5.0, t0, t0)
        assertThat(ReviewStatus.of(review)).isEqualTo(VocabularyStatus.LEARNING)
        assertThat(ReviewStatus.of(review.copy(stability = 7.0))).isEqualTo(VocabularyStatus.FAMILIAR)
        assertThat(ReviewStatus.of(review.copy(stability = 60.0))).isEqualTo(VocabularyStatus.MASTERED)
    }
}
