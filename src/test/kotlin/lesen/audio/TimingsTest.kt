package lesen.audio

import lesen.reading.DraftParagraph
import lesen.reading.DraftSentence
import lesen.reading.FormResolver
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.UnicodeTokenizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimingsTest {
    // p1s1: Mia(t1) wohnt(t2) am(t3) Meer(t4) .(t5) / p1s2: Hallo(t1) !(t2) / p2s1: Ende(t1) .(t2)
    private val content =
        ReadingAssembler
            .assemble(
                ReadingDraft(
                    slug = "test",
                    language = "de",
                    level = "A1",
                    topics = listOf("travel"),
                    title = "T",
                    summary = "S",
                    paragraphs =
                        listOf(
                            DraftParagraph(listOf(DraftSentence("Mia wohnt am Meer."), DraftSentence("Hallo!"))),
                            DraftParagraph(listOf(DraftSentence("Ende."))),
                        ),
                ),
                UnicodeTokenizer(),
                FormResolver { null },
            ).content

    private val good =
        listOf(
            WordTiming("p1s1t1", 100, 300),
            WordTiming("p1s1t2", 300, 600),
            WordTiming("p1s1t3", 650, 700),
            WordTiming("p1s1t4", 700, 1000),
            WordTiming("p1s2t1", 1400, 1800),
            WordTiming("p2s1t1", 2500, 2900),
        )

    @Test
    fun `complete, ordered timings are valid`() {
        val check = Timings.check(content, good, 3000)
        assertThat(check.errors).isEmpty()
        assertThat(check.unaligned).isEmpty()
        assertThat(check.valid).isTrue()
    }

    @Test
    fun `punctuation, unknown tokens, overlaps and out of bounds are errors`() {
        val bad =
            good +
                listOf(
                    WordTiming("p1s1t5", 1000, 1100),
                    WordTiming("p9s9t9", 0, 10),
                ) - good[1] + WordTiming("p1s1t2", 250, 600)
        val check = Timings.check(content, bad + WordTiming("p2s1t1", 2500, 2900), 2800)
        assertThat(check.errors).anyMatch { "signo" in it }
        assertThat(check.errors).anyMatch { "p9s9t9" in it }
        assertThat(check.errors).anyMatch { "empieza antes" in it }
        assertThat(check.errors).anyMatch { "después del audio" in it }
        assertThat(check.errors).anyMatch { "más de un tiempo" in it }
    }

    @Test
    fun `missing words are reported, and too many reject the audio`() {
        val oneMissing = Timings.check(content, good - good.last(), 3000)
        assertThat(oneMissing.unaligned).containsExactly("p2s1t1")
        assertThat(oneMissing.valid).isFalse()
        assertThat(Timings.check(content, emptyList(), 3000).errors).anyMatch { "sin tiempo" in it }
    }

    @Test
    fun `sentence and paragraph ranges come from their first and last timed word`() {
        assertThat(Timings.sentenceRanges(content, good))
            .containsExactly(TimeRange("p1s1", 100, 1000), TimeRange("p1s2", 1400, 1800), TimeRange("p2s1", 2500, 2900))
        assertThat(Timings.paragraphRanges(content, good)).containsExactly(TimeRange("p1", 100, 1800), TimeRange("p2", 2500, 2900))
    }
}
