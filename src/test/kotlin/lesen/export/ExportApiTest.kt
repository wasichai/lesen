package lesen.export

import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ExportApiTest : LesenApiTest() {
    private val base = "/api/lesen/readings/der-alte-leuchtturm/versions/1/export"

    private fun download(
        query: String,
        token: String,
        slug: String = "der-alte-leuchtturm",
    ): Pair<HttpHeaders, ByteArray> {
        val result =
            client
                .get()
                .uri("/api/lesen/readings/$slug/versions/1/export?$query")
                .header("Authorization", token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
        return result.responseHeaders to result.responseBody!!
    }

    @Test
    fun `a reader downloads the reading as an epub with translations, glossary and own notes`() {
        val token = lena()
        ensureProfile(token)
        send(
            "POST",
            "/api/lesen/readings/der-alte-leuchtturm/notes",
            token,
            mapOf("version" to 1, "sentenceRef" to "p1s2", "note" to "Nota para exportar"),
            201,
        )
        val (headers, bytes) = download("format=epub&translations=true&glossary=true&notes=true&language=es", token)
        assertThat(headers.contentType.toString()).isEqualTo("application/epub+zip")
        assertThat(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("der-alte-leuchtturm-v1.epub")
        assertThat(headers.cacheControl).contains("no-store")
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = String(zip.readAllBytes()) }
        }
        assertThat(entries.keys.first()).isEqualTo("mimetype")
        assertThat(entries.keys).contains("OEBPS/images/cover.svg", "OEBPS/glossary.xhtml", "OEBPS/notes.xhtml")
        assertThat(entries.getValue("OEBPS/text.xhtml"))
            .contains("Er wohnt neben einem alten Leuchtturm.")
            .contains("Mia pasa el verano con su abuelo junto al mar del Norte.")
        assertThat(entries.getValue("OEBPS/glossary.xhtml")).contains("Glosario").contains("Leuchtturm")
        assertThat(entries.getValue("OEBPS/notes.xhtml")).contains("Nota para exportar")
    }

    @Test
    fun `the printable page has only what was asked and never someone else's notes`() {
        val token = tomas()
        ensureProfile(token)
        val (headers, bytes) = download("format=html&notes=true&language=en", token)
        val html = String(bytes)
        assertThat(headers.contentType.toString()).startsWith("text/html")
        assertThat(headers.getFirst("Content-Security-Policy")).contains("default-src 'none'")
        assertThat(html)
            .contains("Der alte Leuchtturm")
            .contains("My notes")
            .doesNotContain("Nota para exportar")
            .doesNotContain("Glossary")
        assertThat(html).doesNotContain("class=\"translation")
    }

    @Test
    fun `a picture book exports its pages with their illustrations and alt text in order`() {
        val token = lena()
        ensureProfile(token)
        val (_, bytes) = download("format=epub&language=en", token, "ein-sonntag-bei-oma")
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = String(zip.readAllBytes()) }
        }
        val illustrations = entries.keys.filter { it.startsWith("OEBPS/illustrations/") }
        assertThat(illustrations).isNotEmpty.allMatch { it.endsWith(".svg") }
        val text = entries.getValue("OEBPS/text.xhtml")
        assertThat(text).contains("<section class=\"page\" id=\"g1\">").contains("<section class=\"page wide\" id=\"g2\">")
        assertThat(text).contains("alt=\"Lena gets off a yellow bus").doesNotContain("We could not include")
        assertThat(text.indexOf("id=\"g1\"")).isLessThan(text.indexOf("id=\"g2\""))
        illustrations.forEach { assertThat(entries.getValue("OEBPS/content.opf")).contains("href=\"${it.removePrefix("OEBPS/")}\"") }
    }

    @Test
    fun `the image between paragraphs of the demo travels in the epub after its paragraph`() {
        val token = lena()
        ensureProfile(token)
        val (_, bytes) = download("format=epub&language=es", token, "der-alte-leuchtturm")
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = String(zip.readAllBytes()) }
        }
        assertThat(entries.keys).contains("OEBPS/illustrations/der-alte-leuchtturm-sturm.svg")
        val text = entries.getValue("OEBPS/text.xhtml")
        assertThat(text).contains("alt=\"Ein weißer Leuchtturm").contains("<figcaption>Plötzlich geht das Licht aus.</figcaption>")
        assertThat(text.indexOf("id=\"p3\"")).isLessThan(text.indexOf("id=\"img-1\""))
        assertThat(text.indexOf("id=\"img-1\"")).isLessThan(text.indexOf("id=\"p4\""))
    }

    @Test
    fun `export needs a token, a known format and an existing version, and notes need the notebook`() {
        client
            .get()
            .uri("$base?format=epub")
            .exchange()
            .expectStatus()
            .isUnauthorized
        send("GET", "$base?format=mobi", lena(), null, 400)
        send("GET", "/api/lesen/readings/der-alte-leuchtturm/versions/99/export?format=epub", lena(), null, 404)
        send("GET", "/api/lesen/readings/no-such-reading/versions/1/export?format=html", lena(), null, 404)
        // quien puede leer el catálogo pero no tiene cuaderno exporta el texto, pero no notas
        download("format=html", editor())
        send("GET", "$base?format=html&notes=true", editor(), null, 403)
    }
}
