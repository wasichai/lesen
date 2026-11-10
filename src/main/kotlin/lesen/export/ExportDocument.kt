package lesen.export

import lesen.reading.Paragraph
import lesen.reading.ReadingContent
import java.time.Instant

// lo que se pide incluir además del texto y la portada, que van siempre
data class ExportOptions(
    val translations: Boolean,
    val glossary: Boolean,
    val notes: Boolean,
)

data class ExportCover(
    val file: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    val extension get() = file.substringAfterLast('.')

    override fun equals(other: Any?) = other is ExportCover && other.file == file && other.contentType == contentType && other.bytes.contentEquals(bytes)

    override fun hashCode() = 31 * file.hashCode() + bytes.contentHashCode()
}

// translation: la del párrafo en la lengua pedida, o null si no la hay (y entonces el documento lo dice, ADR-0005)
data class ExportParagraph(
    val id: String,
    val text: String,
    val translation: String?,
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
    val cover: ExportCover?,
    val paragraphs: List<ExportParagraph>,
    val options: ExportOptions,
    val glossary: List<GlossaryItem>,
    val notes: List<ExportNote>,
    val labels: ExportLabels,
    val generatedAt: Instant,
) {
    val identifier get() = "urn:lesen:$slug:v$version"

    val fileName get() = "$slug-v$version"
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

    // las entradas del diccionario que la publicación resolvió para las palabras del texto, sin repetir
    fun entries(content: ReadingContent): Set<String> = content.words.mapNotNull { it.entry }.toCollection(LinkedHashSet())
}
