package lesen.reading

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

class ReadingAssemblerTest {
    private val forms = mapOf("mia" to "de:mia", "wohnt" to "de:wohnen", "am" to "de:an", "meer" to "de:meer", "bank" to "de:bank#money")
    private val resolver = FormResolver { forms[it] }

    private fun draft(vararg sentences: DraftSentence) =
        ReadingDraft(
            slug = "am-meer",
            language = "de",
            level = "A1",
            topics = listOf("travel"),
            title = "Am Meer",
            summary = "Eine kurze Geschichte.",
            paragraphs = listOf(DraftParagraph(sentences.toList(), mapOf("es" to "Párrafo"))),
        )

    @Test
    fun `tokenizes, resolves every word and counts occurrences`() {
        val assembled =
            ReadingAssembler.assemble(
                draft(DraftSentence("Mia wohnt am Meer.", mapOf("es" to "Mia vive junto al mar."))),
                UnicodeTokenizer(),
                resolver,
            )
        val tokens =
            assembled.content.paragraphs[0]
                .sentences[0]
                .tokens
        assertThat(tokens.filter { it.kind == TokenKind.WORD }.map { it.entry }).containsExactly("de:mia", "de:wohnen", "de:an", "de:meer")
        assertThat(assembled.wordCount).isEqualTo(4)
        assertThat(assembled.entryOccurrences).containsEntry("de:meer", 1)
        assertThat(assembled.translations.map { it.unit to it.ref }).containsExactly("PARAGRAPH" to "p1", "SENTENCE" to "p1s1")
        assertThat(assembled.unresolved).isEmpty()
    }

    @Test
    fun `a form without entry is reported, never invented`() {
        val assembled = ReadingAssembler.assemble(draft(DraftSentence("Mia schwimmt.")), UnicodeTokenizer(), resolver)
        assertThat(assembled.unresolved).containsExactly("schwimmt")
        assertThat(
            assembled.content.words
                .last()
                .entry,
        ).isNull()
    }

    @Test
    fun `a sentence sense override beats the default entry`() {
        val assembled = ReadingAssembler.assemble(draft(DraftSentence("Mia am Bank.", senses = mapOf("Bank" to "de:bank#seat"))), UnicodeTokenizer(), resolver)
        assertThat(
            assembled.content.words
                .last()
                .entry,
        ).isEqualTo("de:bank#seat")
    }

    @Test
    fun `same reading same hash, other text other hash`() {
        val a = draft(DraftSentence("Mia wohnt am Meer."))
        assertThat(ReadingAssembler.hash(a)).isEqualTo(ReadingAssembler.hash(a.copy(featured = true)))
        assertThat(ReadingAssembler.hash(a)).isNotEqualTo(ReadingAssembler.hash(draft(DraftSentence("Mia wohnt am See."))))
    }

    @Test
    fun `rejects an incomplete draft naming every problem`() {
        val bad = draft(DraftSentence("  ")).copy(slug = "Am Meer", level = "Z9", topics = emptyList(), title = "")
        assertThatThrownBy { ReadingAssembler.validate(bad) }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ error ->
                assertThat((error as ValidationException).violations.map { it.field })
                    .contains("slug", "level", "title", "topics", "paragraphs[0].sentences[0]")
            })
    }

    @Test
    fun `minutes round up and never go below one`() {
        assertThat(ReadingTime.minutes(140, 75)).isEqualTo(2)
        assertThat(ReadingTime.minutes(10, 150)).isEqualTo(1)
        assertThat(ReadingTime.remaining(5, 50)).isEqualTo(3)
        assertThat(ReadingTime.remaining(5, 100)).isEqualTo(0)
    }
}
