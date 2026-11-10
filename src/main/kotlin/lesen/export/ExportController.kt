package lesen.export

import lesen.common.Access
import lesen.common.Learner
import lesen.common.Media
import lesen.common.MediaKind
import lesen.dictionary.DictionaryService
import lesen.library.LibraryService
import lesen.reading.ReadingVersions
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Clock

enum class ExportFormat { EPUB, HTML }

// reúne una versión publicada con lo que se pidió incluir. Las traducciones y el glosario salen de lo curado (ADR-0005):
// lo que falta se dice en el documento, no se completa. Las notas son las de quien llama, filtradas por su organización
@Service
class ExportService(
    private val versions: ReadingVersions,
    private val dictionary: DictionaryService,
    private val library: LibraryService,
    private val media: Media,
    private val clock: Clock,
) {
    suspend fun document(
        me: Learner,
        notebook: Learner?,
        slug: String,
        number: Int,
        options: ExportOptions,
        language: String?,
    ): ExportDocument {
        val into = dictionary.translationLanguage(me, language)
        val ref = versions.find(slug, number) ?: throw NotFoundException("Reading version not found")
        val meta = versions.meta(ref.versionId) ?: throw NotFoundException("Reading version not found")
        val content = versions.content(ref.versionId)
        val paragraphs =
            if (options.translations) {
                ExportParagraphs.of(
                    content,
                    versions.translations(ref.versionId, "PARAGRAPH", into, null).associate { it.ref to it.text },
                    versions.translations(ref.versionId, "SENTENCE", into, null).associate { it.ref to it.text },
                    true,
                )
            } else {
                ExportParagraphs.of(content, emptyMap(), emptyMap(), false)
            }
        val glossary =
            if (options.glossary) {
                dictionary.glossary(ExportParagraphs.entries(content), into).map { GlossaryItem(it.lemma, it.pos, it.translations) }
            } else {
                emptyList()
            }
        val notes =
            if (options.notes && notebook != null) {
                library.notes(notebook, slug).map { ExportNote(it.quote, it.note) }
            } else {
                emptyList()
            }
        return ExportDocument(
            slug = ref.slug,
            version = ref.number,
            language = ref.language,
            level = meta.level,
            title = meta.title,
            subtitle = meta.subtitle,
            summary = meta.summary,
            cover = meta.cover?.let { cover(it) },
            paragraphs = paragraphs,
            options = options,
            glossary = glossary,
            notes = notes,
            labels = ExportLabels.of(into),
            generatedAt = clock.instant(),
        )
    }

    // la portada de la demostración (SVG sin texto, del classpath) o la subida desde el estudio; si no está, sin portada
    private suspend fun cover(file: String): ExportCover? {
        if (!COVER.matches(file)) return null
        if (file.endsWith(".svg")) {
            val resource = ClassPathResource("demo/covers/$file").takeIf { it.exists() } ?: return null
            return ExportCover(file, "image/svg+xml", resource.inputStream.use { it.readAllBytes() })
        }
        val stored = media.find(file)?.takeIf { it.kind == MediaKind.COVER } ?: return null
        return media.bytes(stored)?.let { ExportCover(file, stored.contentType, it) }
    }

    companion object {
        private val COVER = Regex("^[a-z0-9]+(-[a-z0-9]+)*\\.(svg|png|jpg|webp)$")
    }
}

// exportar una lectura para abrirla en otras aplicaciones (ADR-0014): ePub para Apple Books, Kindle y demás, o una página
// para imprimir o guardar como PDF. Basta poder leer; las notas exigen además poder leer lo propio
@RestController
@RequestMapping("/api/lesen")
class ExportController(
    private val access: Access,
    private val service: ExportService,
) {
    @GetMapping("/readings/{slug}/versions/{number}/export")
    suspend fun export(
        @PathVariable slug: String,
        @PathVariable number: Int,
        @RequestParam format: String,
        @RequestParam(defaultValue = "false") translations: Boolean,
        @RequestParam(defaultValue = "false") glossary: Boolean,
        @RequestParam(defaultValue = "false") notes: Boolean,
        @RequestParam language: String?,
    ): ResponseEntity<ByteArray> {
        val me = access.reader()
        val kind =
            ExportFormat.entries.firstOrNull { it.name.equals(format, ignoreCase = true) }
                ?: throw ValidationException("Invalid format", "format", "must be epub or html")
        val notebook = if (notes) access.notebook() else null
        val doc = service.document(me, notebook, slug, number, ExportOptions(translations, glossary, notes), language)
        val builder =
            ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
        return when (kind) {
            ExportFormat.EPUB ->
                builder
                    .contentType(MediaType.parseMediaType(EpubWriter.MEDIA_TYPE))
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition
                            .attachment()
                            .filename("${doc.fileName}.epub")
                            .build()
                            .toString(),
                    ).body(EpubWriter.write(doc))
            ExportFormat.HTML ->
                builder
                    .contentType(MediaType("text", "html", Charsets.UTF_8))
                    .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition
                            .inline()
                            .filename("${doc.fileName}.html")
                            .build()
                            .toString(),
                    ).body(XhtmlWriter.printable(doc).toByteArray(Charsets.UTF_8))
        }
    }
}
