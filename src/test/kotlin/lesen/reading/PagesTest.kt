package lesen.reading

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

// un libro ilustrado (ADR-0014): páginas que reparten los párrafos, una maqueta por defecto y otras por página
class PagesTest {
    private val resolver = FormResolver { null }

    private fun paragraph(text: String) = DraftParagraph(listOf(DraftSentence(text)))

    private fun book(
        vararg pages: DraftPage,
        layout: PageLayout? = PageLayout.IMAGE_TOP,
    ) = ReadingDraft(
        slug = "ein-buch",
        language = "de",
        level = "A1",
        topics = listOf("travel"),
        title = "Ein Buch",
        summary = "Kurz.",
        paragraphs = listOf(paragraph("Eins."), paragraph("Zwei."), paragraph("Drei.")),
        format = ReadingFormat.PICTURE_BOOK,
        layout = layout,
        pages = pages.toList(),
    )

    private fun art(file: String? = "bild.png") = DraftIllustration(file, mapOf("es" to "Una imagen", "en" to "A picture"), "Mia am Meer")

    @Test
    fun `pages take consecutive paragraphs and resolve the book layout`() {
        val draft =
            book(
                DraftPage(paragraphs = 2, illustration = art("eins.png")),
                DraftPage(span = 2, layout = PageLayout.PANORAMA, paragraphs = 1, illustration = art("zwei.png")),
                DraftPage(layout = PageLayout.IMAGE_ONLY, paragraphs = 0, illustration = art("drei.png")),
            )
        val content = ReadingAssembler.assemble(draft, UnicodeTokenizer(), resolver).content
        assertThat(content.format).isEqualTo(ReadingFormat.PICTURE_BOOK)
        assertThat(content.layout).isEqualTo(PageLayout.IMAGE_TOP)
        assertThat(content.pages!!.map { Triple(it.id, it.layout, it.paragraphs) }).containsExactly(
            Triple("g1", PageLayout.IMAGE_TOP, listOf("p1", "p2")),
            Triple("g2", PageLayout.PANORAMA, listOf("p3")),
            Triple("g3", PageLayout.IMAGE_ONLY, emptyList()),
        )
        assertThat(content.pages!![1].illustration).isEqualTo(Illustration("zwei.png", mapOf("es" to "Una imagen", "en" to "A picture")))
        // los ids del texto no cambian por estar en un libro (ADR-0004)
        assertThat(content.paragraphs.map { it.id }).containsExactly("p1", "p2", "p3")
    }

    @Test
    fun `the pages must hold every paragraph and each layout must fit its span`() {
        val draft =
            book(
                DraftPage(paragraphs = 1),
                DraftPage(span = 2, paragraphs = 1),
                DraftPage(layout = PageLayout.IMAGE_ONLY, paragraphs = 1),
            )
        assertThatThrownBy { ReadingAssembler.validate(draft) }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ error ->
                val fields = (error as ValidationException).violations.map { it.field }
                assertThat(fields).contains("pages[1].layout", "pages[2].paragraphs")
            })
        assertThat(PageRules.problems(book(DraftPage(paragraphs = 2))).map { it.field }).containsExactly("pages")
        assertThat(PageRules.problems(book(DraftPage(paragraphs = 3), layout = null)).map { it.field }).containsExactly("layout")
    }

    @Test
    fun `a text keeps its old hash, and a scene note does not change a book's`() {
        val text = book().copy(format = ReadingFormat.TEXT, layout = null)
        val legacy =
            lesen.common.Jsonb.MAPPER
                .valueToTree<tools.jackson.databind.node.ObjectNode>(text.copy(featured = false))
                .also { it.remove(listOf("format", "layout", "pages")) }
        val expected =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    lesen.common.Jsonb.MAPPER
                        .writeValueAsString(legacy)
                        .toByteArray(),
                ).joinToString("") { "%02x".format(it) }
        assertThat(ReadingAssembler.hash(text)).isEqualTo(expected)
        val withScene = book(DraftPage(paragraphs = 3, illustration = art()))
        val otherScene = book(DraftPage(paragraphs = 3, illustration = art().copy(scene = "Otra cosa")))
        val otherFile = book(DraftPage(paragraphs = 3, illustration = art("anders.png")))
        assertThat(ReadingAssembler.hash(withScene)).isEqualTo(ReadingAssembler.hash(otherScene))
        assertThat(ReadingAssembler.hash(withScene)).isNotEqualTo(ReadingAssembler.hash(otherFile))
    }

    @Test
    fun `a text reading has no pages, and a pending illustration is reported, never invented`() {
        assertThat(PageRules.problems(book(DraftPage(paragraphs = 3)).copy(format = ReadingFormat.TEXT)).map { it.field }).containsExactly("pages", "layout")
        val draft = book(DraftPage(paragraphs = 2, illustration = art(null)), DraftPage(layout = PageLayout.TEXT_ONLY, paragraphs = 1))
        assertThat(PageRules.missingIllustrations(draft)).containsExactly(0)
        assertThat(
            ReadingAssembler
                .assemble(draft, UnicodeTokenizer(), resolver)
                .content.pages!!
                .map { it.illustration },
        ).containsOnlyNulls()
    }

    @Test
    fun `a published book comes back as the same pages`() {
        val draft =
            book(
                DraftPage(paragraphs = 2, illustration = art("eins.png")),
                DraftPage(span = 2, layout = PageLayout.SPREAD_IMAGE_LEFT, paragraphs = 1, illustration = art("zwei.png")),
            )
        val content = ReadingAssembler.assemble(draft, UnicodeTokenizer(), resolver).content
        val back = PageRules.draftPages(content.layout, content.pages!!)
        assertThat(back).containsExactlyElementsOf(draft.pages.map { it.copy(illustration = it.illustration?.copy(scene = null)) })
    }
}
