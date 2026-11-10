package lesen.reading

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

// las imágenes entre párrafos de un texto (ADR-0018): ancladas a un párrafo, sin tocar los ids ni la huella del texto
class InlineImagesTest {
    private val resolver = FormResolver { null }

    private fun text(vararg images: DraftImage) =
        ReadingDraft(
            slug = "ein-text",
            language = "de",
            level = "A1",
            topics = listOf("travel"),
            title = "Ein Text",
            summary = "Kurz.",
            paragraphs = listOf("Eins.", "Zwei.", "Drei.").map { DraftParagraph(listOf(DraftSentence(it))) },
            images = images.toList(),
        )

    private fun image(
        after: Int,
        file: String? = "bild.png",
        alt: String = "Ein Leuchtturm",
    ) = DraftImage(after, file, 1200, 800, alt, "Der Turm")

    @Test
    fun `images keep the paragraph ids and land after their paragraph, in reading order`() {
        val content = ReadingAssembler.assemble(text(image(3), image(0, "zuerst.png"), image(1)), UnicodeTokenizer(), resolver).content
        assertThat(content.paragraphs.map { it.id }).containsExactly("p1", "p2", "p3")
        assertThat(content.images!!.map { Triple(it.id, it.after, it.file) }).containsExactly(
            Triple("img-1", null, "zuerst.png"),
            Triple("img-2", "p1", "bild.png"),
            Triple("img-3", "p3", "bild.png"),
        )
        assertThat(content.images!!.first()).extracting("width", "height", "alt", "caption").containsExactly(1200, 800, "Ein Leuchtturm", "Der Turm")
        // de vuelta al borrador, los mismos sitios
        assertThat(InlineImages.draftImages(content.images!!, content.paragraphs).map { it.after }).containsExactly(0, 1, 3)
    }

    @Test
    fun `a text without images keeps its hash, and an image changes it`() {
        val plain = text()
        val legacy =
            lesen.common.Jsonb.MAPPER
                .valueToTree<tools.jackson.databind.node.ObjectNode>(plain.copy(featured = false))
                .also { it.remove(listOf("format", "layout", "pages", "images")) }
        val expected =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    lesen.common.Jsonb.MAPPER
                        .writeValueAsString(legacy)
                        .toByteArray(),
                ).joinToString("") { "%02x".format(it) }
        assertThat(ReadingAssembler.hash(plain)).isEqualTo(expected)
        assertThat(ReadingAssembler.hash(text(image(1)))).isNotEqualTo(expected)
        assertThat(ReadingAssembler.assemble(plain, UnicodeTokenizer(), resolver).content.images).isNull()
    }

    @Test
    fun `an image needs its place, its size and stays out of picture books`() {
        assertThat(InlineImages.problems(text(image(4))).map { it.field }).containsExactly("images[0].after")
        assertThat(InlineImages.problems(text(DraftImage(1, "bild.png", null, 800, "x"))).map { it.field }).containsExactly("images[0].width")
        assertThat(InlineImages.problems(text(image(1, file = "../etc"))).map { it.field }).containsExactly("images[0].file")
        assertThat(InlineImages.problems(text(image(1, alt = "x".repeat(301)))).map { it.field }).containsExactly("images[0].alt")
        val book = text(image(1)).copy(format = ReadingFormat.PICTURE_BOOK, layout = PageLayout.TEXT_ONLY, pages = listOf(DraftPage(paragraphs = 3)))
        assertThatThrownBy { ReadingAssembler.validate(book) }.isInstanceOf(ValidationException::class.java)
        // pendientes: sin archivo o sin texto alternativo no se publican, pero se pueden guardar
        val pending = text(image(1, file = null), image(2, alt = " "))
        assertThat(InlineImages.problems(pending)).isEmpty()
        assertThat(InlineImages.missingFiles(pending)).containsExactly(0)
        assertThat(InlineImages.missingAlt(pending)).containsExactly(1)
        assertThat(
            ReadingAssembler
                .assemble(pending, UnicodeTokenizer(), resolver)
                .content.images!!
                .map { it.id },
        ).containsExactly("img-1")
    }
}
