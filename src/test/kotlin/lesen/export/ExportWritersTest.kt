package lesen.export

import lesen.reading.Illustration
import lesen.reading.Page
import lesen.reading.PageLayout
import lesen.reading.Paragraph
import lesen.reading.ReadingContent
import lesen.reading.Sentence
import lesen.reading.Token
import lesen.reading.TokenKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportWritersTest {
    private val content =
        ReadingContent(
            listOf(
                Paragraph(
                    "p1",
                    listOf(
                        Sentence("p1s1", "Mia <liest>.", listOf(Token("p1s1t1", "Mia", TokenKind.WORD, 0, 3, true, "de:mia"))),
                        Sentence("p1s2", "Sie lacht.", listOf(Token("p1s2t1", "lacht", TokenKind.WORD, 4, 9, false, "de:lachen"))),
                    ),
                ),
                Paragraph("p2", listOf(Sentence("p2s1", "Ende.", listOf(Token("p2s1t1", "Ende", TokenKind.WORD, 0, 4, false, "de:lachen"))))),
            ),
        )

    private fun doc(
        options: ExportOptions = ExportOptions(translations = true, glossary = true, notes = true),
        cover: ExportImage? = ExportImage("x.svg", "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()),
        pages: List<Page>? = null,
    ) = ExportDocument(
        slug = "x",
        version = 2,
        language = "de",
        level = "A2",
        title = "Tom & \"Jerry\"",
        subtitle = null,
        summary = "Kurz.",
        cover = cover,
        paragraphs = ExportParagraphs.of(content, emptyMap(), mapOf("p1s1" to "Mia lee.", "p1s2" to "Ríe."), options.translations),
        pages =
            ExportParagraphs.pages(
                pages,
                ExportParagraphs.of(content, emptyMap(), emptyMap(), false),
                mapOf("bild-1.png" to ExportImage("bild-1.png", "image/png", byteArrayOf(1, 2, 3))),
                "es",
            ),
        options = options,
        glossary = listOf(GlossaryItem("lachen", "VERB", listOf("reír"))),
        notes = listOf(ExportNote("Sie lacht.", "¡Bonito!")),
        labels = ExportLabels.of("es"),
        generatedAt = Instant.parse("2026-11-10T10:15:30.123Z"),
    )

    @Test
    fun `a paragraph is translated by its own translation or by all of its sentences, never partly`() {
        val paragraphs = ExportParagraphs.of(content, mapOf("p2" to "Fin."), mapOf("p1s1" to "Mia lee."), true)
        assertThat(paragraphs[0].text).isEqualTo("Mia <liest>. Sie lacht.")
        assertThat(paragraphs[0].translation).isNull()
        assertThat(paragraphs[1].translation).isEqualTo("Fin.")
        assertThat(ExportParagraphs.of(content, emptyMap(), mapOf("p1s1" to "Mia lee.", "p1s2" to "Ríe."), true)[0].translation).isEqualTo("Mia lee. Ríe.")
        assertThat(ExportParagraphs.of(content, mapOf("p2" to "Fin."), emptyMap(), false).map { it.translation }).containsOnlyNulls()
        assertThat(ExportParagraphs.entries(content)).containsExactly("de:mia", "de:lachen")
    }

    @Test
    fun `the printable page escapes text, marks languages and says what is missing`() {
        val html = XhtmlWriter.printable(doc())
        assertThat(html).contains("Tom &amp; &quot;Jerry&quot;").contains("Mia &lt;liest&gt;.").doesNotContain("<liest>")
        assertThat(html).contains("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"de\"")
        assertThat(html).contains("<p class=\"translation\" lang=\"es\" xml:lang=\"es\">Mia lee. Ríe.</p>")
        assertThat(html).contains("Este párrafo no tiene traducción.")
        assertThat(html).contains("Glosario").contains("Mis notas").contains("¡Bonito!")
        assertThat(html).contains("src=\"data:image/svg+xml;base64,")
        assertThat(html).contains("@media print")
    }

    @Test
    fun `sections not asked for are left out`() {
        val html = XhtmlWriter.printable(doc(ExportOptions(translations = false, glossary = false, notes = false), cover = null))
        assertThat(html)
            .doesNotContain("class=\"translation")
            .doesNotContain("Glosario")
            .doesNotContain("Mis notas")
            .doesNotContain("<img")
        assertThat(XhtmlWriter.sections(doc(ExportOptions(false, false, true))).map { it.first }).containsExactly("text.xhtml", "notes.xhtml")
    }

    @Test
    fun `the epub starts with an uncompressed mimetype and its package files are well formed`() {
        val bytes = EpubWriter.write(doc())
        val entries = linkedMapOf<String, Pair<Int, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = it.method to zip.readAllBytes() }
        }
        val first = entries.entries.first()
        assertThat(first.key).isEqualTo("mimetype")
        assertThat(first.value.first).isEqualTo(ZipEntry.STORED)
        assertThat(String(first.value.second, Charsets.US_ASCII)).isEqualTo("application/epub+zip")
        assertThat(bytes.copyOfRange(30, 38).toString(Charsets.US_ASCII)).isEqualTo("mimetype")
        assertThat(entries.keys).contains(
            "META-INF/container.xml",
            "OEBPS/content.opf",
            "OEBPS/nav.xhtml",
            "OEBPS/style.css",
            "OEBPS/images/cover.svg",
            "OEBPS/cover.xhtml",
            "OEBPS/text.xhtml",
            "OEBPS/glossary.xhtml",
            "OEBPS/notes.xhtml",
        )
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".opf") || it.endsWith(".xhtml") || it.endsWith(".svg") }.forEach { (name, value) ->
            val xml = parser.parse(ByteArrayInputStream(value.second))
            assertThat(xml.documentElement).describedAs(name).isNotNull
        }
        val opf = String(entries.getValue("OEBPS/content.opf").second)
        assertThat(opf).contains("<dc:identifier id=\"uid\">urn:lesen:x:v2</dc:identifier>")
        assertThat(opf).contains("<meta property=\"dcterms:modified\">2026-11-10T10:15:30Z</meta>")
        assertThat(opf).contains("properties=\"cover-image\"")
        assertThat(opf).contains("<itemref idref=\"text\"/>").contains("<itemref idref=\"glossary\"/>").contains("<itemref idref=\"notes\"/>")
    }

    private val book =
        listOf(
            Page("g1", 1, PageLayout.IMAGE_TOP, listOf("p1"), Illustration("bild-1.png", mapOf("es" to "Mia lee", "en" to "Mia reads"))),
            Page("g2", 2, PageLayout.IMAGE_ONLY, emptyList(), Illustration("bild-2.png", mapOf("en" to "A garden"))),
            Page("g3", 1, PageLayout.TEXT_ONLY, listOf("p2"), null),
        )

    @Test
    fun `a picture book keeps the reading order, its alt text and says when an illustration is missing`() {
        val pages = doc(pages = book).pages!!
        assertThat(pages.map { it.id }).containsExactly("g1", "g2", "g3")
        assertThat(pages.map { it.paragraphs.map { p -> p.id } }).containsExactly(listOf("p1"), emptyList(), listOf("p2"))
        assertThat(pages.map { it.alt }).containsExactly("Mia lee", "A garden", "")
        assertThat(pages.map { it.missing }).containsExactly(false, true, false)
        assertThat(pages[1].wide).isTrue
        assertThat(ExportParagraphs.pages(null, emptyList(), emptyMap(), "es")).isNull()

        val html = XhtmlWriter.printable(doc(pages = book))
        assertThat(html).contains("<section class=\"page\" id=\"g1\">").contains("<section class=\"page wide\" id=\"g2\">")
        assertThat(html).contains("src=\"data:image/png;base64,AQID\" alt=\"Mia lee\" lang=\"es\"")
        assertThat(html).contains("No pudimos incluir la ilustración de esta página.")
        assertThat(html.indexOf("id=\"g1\"")).isLessThan(html.indexOf("id=\"p1\"")).isLessThan(html.indexOf("id=\"g3\""))
    }

    @Test
    fun `the epub carries each illustration once and lists it in the package`() {
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(EpubWriter.write(doc(pages = book)))).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries += it.name }
        }
        assertThat(entries).containsOnlyOnce("OEBPS/illustrations/bild-1.png").doesNotContain("OEBPS/illustrations/bild-2.png")
        val opf = EpubWriter.opf(doc(pages = book))
        assertThat(opf).contains("href=\"illustrations/bild-1.png\" media-type=\"image/png\"")
        assertThat(XhtmlWriter.text(doc(pages = book))).contains("<img src=\"illustrations/bild-1.png\" alt=\"Mia lee\"")
    }

    @Test
    fun `control characters never reach the xml`() {
        assertThat(XhtmlWriter.esc("a\u0001b\tc\n")).isEqualTo("ab\tc\n")
    }
}
