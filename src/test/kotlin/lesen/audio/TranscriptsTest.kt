package lesen.audio

import lesen.reading.DraftParagraph
import lesen.reading.DraftSentence
import lesen.reading.FormResolver
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.UnicodeTokenizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

// los tiempos subidos desde el estudio: lo dicho se casa con lo escrito, y lo que no casa queda sin tiempo
class TranscriptsTest {
    // p1s1: Mia(t1) wohnt(t2) am(t3) Meer(t4) .(t5) / p1s2: Hallo(t1) !(t2)
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
                    paragraphs = listOf(DraftParagraph(listOf(DraftSentence("Mia wohnt am Meer."), DraftSentence("Hallo!")))),
                ),
                UnicodeTokenizer(),
                FormResolver { null },
            ).content

    @Test
    fun `a word-level WebVTT is matched to the words of the text`() {
        val vtt =
            """
            WEBVTT

            1
            00:00:00.100 --> 00:00:00.300
            Mia

            00:00:00.300 --> 00:00:00.600
            <c>wohnt</c>

            00:00:00.650 --> 00:00:00.700
            am

            00:00:00.700 --> 00:00:01.000
            Meer.

            00:00:01.400 --> 00:00:01.800
            hallo
            """.trimIndent()
        val alignment = Transcripts.align(vtt, content)
        assertThat(alignment.timings).containsExactly(
            WordTiming("p1s1t1", 100, 300),
            WordTiming("p1s1t2", 300, 600),
            WordTiming("p1s1t3", 650, 700),
            WordTiming("p1s1t4", 700, 1000),
            WordTiming("p1s2t1", 1400, 1800),
        )
        assertThat(alignment.multiWordCues).isZero()
        assertThat(Timings.check(content, alignment.timings, 2000).valid).isTrue()
    }

    @Test
    fun `an SRT with a missing word and an extra word leaves only those without time`() {
        val srt =
            """
            1
            00:00:00,100 --> 00:00:00,300
            Mia

            2
            00:00:00,300 --> 00:00:00,500
            ähm

            3
            00:00:00,700 --> 00:00:01,000
            Meer

            4
            00:00:01,400 --> 00:00:01,800
            Hallo
            """.trimIndent()
        val alignment = Transcripts.align(srt, content)
        assertThat(alignment.timings.map { it.tokenId }).containsExactly("p1s1t1", "p1s1t4", "p1s2t1")
        assertThat(alignment.extraWords).isEqualTo(1)
    }

    @Test
    fun `a cue with several words gives them no time instead of guessing`() {
        val vtt = "WEBVTT\n\n00:00.100 --> 00:01.000\nMia wohnt am Meer\n\n00:01.400 --> 00:01.800\nHallo\n"
        val alignment = Transcripts.align(vtt, content)
        assertThat(alignment.timings.map { it.tokenId }).containsExactly("p1s2t1")
        assertThat(alignment.multiWordCues).isEqualTo(1)
    }

    @Test
    fun `the JSON of generate_audio is read as it is`() {
        val json = """{"voice": {"id": "x"}, "timings": {"p1s2t1": [1400, 1800], "p1s1t1": [100, 300]}}"""
        assertThat(Transcripts.align(json, content).timings).containsExactly(WordTiming("p1s1t1", 100, 300), WordTiming("p1s2t1", 1400, 1800))
    }

    @Test
    fun `anything else is refused`() {
        assertThatThrownBy { Transcripts.align("hola, esto no tiene tiempos", content) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Transcripts.align("{not json", content) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
