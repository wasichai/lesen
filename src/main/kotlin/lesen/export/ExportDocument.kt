package lesen.export

import lesen.reading.Page
import lesen.reading.Paragraph
import lesen.reading.ReadingContent
import java.time.Instant

// lo que se pide incluir además del texto y la portada, que van siempre
data class ExportOptions(
    val translations: Boolean,
    val glossary: Boolean,
    val notes: Boolean,
)

// una imagen que viaja dentro del documento: la portada o la ilustración de una página
data class ExportImage(
    val file: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    val extension get() = file.substringAfterLast('.')

    override fun equals(other: Any?) = other is ExportImage && other.file == file && other.contentType == contentType && other.bytes.contentEquals(bytes)

    override fun hashCode() = 31 * file.hashCode() + bytes.contentHashCode()
}

// translation: la del párrafo en la lengua pedida, o null si no la hay (y entonces el documento lo dice, ADR-0005)
data class ExportParagraph(
    val id: String,
    val text: String,
    val translation: String?,
)

// una página de un libro ilustrado (ADR-0014), en el orden de lectura. wide: una doble página, con la imagen a todo el
// ancho. illustration: null si la maqueta no lleva; missing: la lleva pero su archivo no se pudo leer, y se dice
data class ExportPage(
    val id: String,
    val wide: Boolean,
    val paragraphs: List<ExportParagraph>,
    val illustration: ExportImage?,
    val alt: String,
    val missing: Boolean,
)

data class GlossaryItem(
    val lemma: String,
    val pos: String,
    val translations: List<String>,
)

data class ExportNote(
    val quote: String,
    val note: String?,
)

// una lectura lista para escribirse como ePub o como HTML para imprimir. language: la del texto (idioma de estudio);
// labels: los rótulos en la lengua de la interfaz, que es también la de las traducciones y el glosario
data class ExportDocument(
    val slug: String,
    val version: Int,
    val language: String,
    val level: String,
    val title: String,
    val subtitle: String?,
    val summary: String,
    val cover: ExportImage?,
    val paragraphs: List<ExportParagraph>,
    // las páginas de un libro ilustrado; null en un texto
    val pages: List<ExportPage>?,
    val options: ExportOptions,
    val glossary: List<GlossaryItem>,
    val notes: List<ExportNote>,
    val labels: ExportLabels,
    val generatedAt: Instant,
) {
    val identifier get() = "urn:lesen:$slug:v$version"

    val fileName get() = "$slug-v$version"

    // las ilustraciones que viajan en el documento, una vez cada una
    val illustrations get() = pages.orEmpty().mapNotNull { it.illustration }.distinctBy { it.file }
}

// los rótulos del documento en las dos lenguas de la interfaz (ADR-0007)
data class ExportLabels(
    val language: String,
    val translation: String,
    val noTranslation: String,
    val glossary: String,
    val glossaryEmpty: String,
    val notes: String,
    val notesEmpty: String,
    val contents: String,
    val cover: String,
    val level: String,
    val source: String,
    val illustrationMissing: String,
) {
    companion object {
        fun of(language: String): ExportLabels =
            if (language == "es") {
                ExportLabels(
                    "es",
                    "Traducción",
                    "Este párrafo no tiene traducción.",
                    "Glosario",
                    "Ninguna palabra de esta lectura está en el diccionario.",
                    "Mis notas",
                    "Aún no tienes notas en esta lectura.",
                    "Índice",
                    "Portada",
                    "Nivel",
                    "Exportado de Lesen",
                    "No pudimos incluir la ilustración de esta página.",
                )
            } else {
                ExportLabels(
                    "en",
                    "Translation",
                    "This paragraph has no translation.",
                    "Glossary",
                    "None of the words in this reading are in the dictionary.",
                    "My notes",
                    "You have no notes in this reading yet.",
                    "Contents",
                    "Cover",
                    "Level",
                    "Exported from Lesen",
                    "We could not include the illustration of this page.",
                )
            }
    }
}

object ExportParagraphs {
    // el texto de cada párrafo y, si se pide, su traducción: la del párrafo o, si falta, la de todas sus oraciones.
    // Si falta alguna oración no se completa con nada: null, y el documento dice que no hay traducción
    fun of(
        content: ReadingContent,
        paragraphTranslations: Map<String, String>,
        sentenceTranslations: Map<String, String>,
        withTranslations: Boolean,
    ): List<ExportParagraph> =
        content.paragraphs.map { p ->
            ExportParagraph(p.id, text(p), if (withTranslations) translation(p, paragraphTranslations, sentenceTranslations) else null)
        }

    fun text(paragraph: Paragraph): String = paragraph.sentences.joinToString(" ") { it.text.trim() }

    private fun translation(
        paragraph: Paragraph,
        paragraphs: Map<String, String>,
        sentences: Map<String, String>,
    ): String? {
        paragraphs[paragraph.id]?.let { return it }
        val parts = paragraph.sentences.map { sentences[it.id] ?: return null }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    // las páginas de un libro en el orden de lectura, con sus párrafos y su ilustración. images: los bytes leídos por
    // nombre de archivo (falta uno: la página lo dice). alt: el de la lengua de la interfaz o, si falta, el inglés
    fun pages(
        pages: List<Page>?,
        paragraphs: List<ExportParagraph>,
        images: Map<String, ExportImage>,
        language: String,
    ): List<ExportPage>? {
        if (pages == null) return null
        val byId = paragraphs.associateBy { it.id }
        return pages.map { page ->
            val file = page.illustration?.file
            val alt =
                page.illustration
                    ?.alt
                    ?.let { it[language] ?: it["en"] ?: it.values.firstOrNull() }
                    .orEmpty()
            ExportPage(
                id = page.id,
                wide = page.span == 2,
                paragraphs = page.paragraphs.mapNotNull { byId[it] },
                illustration = file?.let { images[it] },
                alt = alt,
                missing = file != null && images[file] == null,
            )
        }
    }

    // las entradas del diccionario que la publicación resolvió para las palabras del texto, sin repetir
    fun entries(content: ReadingContent): Set<String> = content.words.mapNotNull { it.entry }.toCollection(LinkedHashSet())
}
