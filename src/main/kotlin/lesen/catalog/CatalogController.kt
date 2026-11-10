package lesen.catalog

import lesen.common.Access
import lesen.common.Media
import lesen.common.MediaKind
import lesen.common.Page
import lesen.common.PageRequest
import lesen.common.requireLength
import lesen.learner.ProfileService
import lesen.reading.IllustrationFiles
import lesen.reading.ReadingFormat
import lesen.reading.ReadingVersions
import lesen.reading.TranslationUnit
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Duration

data class ContentToken(
    val id: String,
    val text: String,
    val kind: String,
    val space: Boolean,
    val entry: String?,
)

data class ContentSentence(
    val id: String,
    val tokens: List<ContentToken>,
)

data class ContentParagraph(
    val id: String,
    val sentences: List<ContentSentence>,
)

// la ilustración de una página: su dirección pública y su texto alternativo por idioma de interfaz
data class ContentIllustration(
    val src: String,
    val alt: Map<String, String>,
)

// una página de un libro ilustrado (ADR-0014): la maqueta ya resuelta y los ids de sus párrafos
data class ContentPage(
    val id: String,
    val span: Int,
    val layout: String,
    val paragraphs: List<String>,
    val illustration: ContentIllustration?,
)

// el texto de una versión, listo para pintar palabra por palabra
data class ReadingContentView(
    val slug: String,
    val version: Int,
    val language: String,
    val current: Boolean,
    val paragraphs: List<ContentParagraph>,
    val format: String = "TEXT",
    val layout: String? = null,
    val pages: List<ContentPage>? = null,
    // el orden de lectura cuando hay imágenes entre párrafos (ADR-0018): {type: paragraph, id} remite al párrafo de
    // paragraphs; {type: image, …} es la imagen. null si el texto no tiene imágenes
    val blocks: List<ContentBlock>? = null,
)

// un bloque del orden de lectura: un párrafo (por su id) o una imagen con todo lo que hace falta para pintarla
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class ContentBlock(
    val type: String,
    val id: String,
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val alt: String? = null,
    val caption: String? = null,
) {
    companion object {
        fun of(content: lesen.reading.ReadingContent): List<ContentBlock>? {
            val images = content.images?.takeIf { it.isNotEmpty() } ?: return null

            fun image(i: lesen.reading.InlineImage) = ContentBlock("image", i.id, IllustrationFiles.url(i.file), i.width, i.height, i.alt, i.caption)
            val byParagraph = images.groupBy { it.after }
            return byParagraph[null].orEmpty().map(::image) +
                content.paragraphs.flatMap { p -> listOf(ContentBlock("paragraph", p.id)) + byParagraph[p.id].orEmpty().map(::image) }
        }
    }
}

data class TranslationView(
    val unit: String,
    val ref: String,
    val language: String,
    val text: String,
)

@RestController
@RequestMapping("/api/lesen")
class CatalogController(
    private val access: Access,
    private val catalog: CatalogService,
    private val versions: ReadingVersions,
    private val profiles: ProfileService,
) {
    @GetMapping("/discover")
    suspend fun discover(): Discover = catalog.discover(access.reader())

    @GetMapping("/readings")
    suspend fun readings(
        @RequestParam language: String?,
        @RequestParam level: List<String>?,
        @RequestParam topic: String?,
        @RequestParam minMinutes: Int?,
        @RequestParam maxMinutes: Int?,
        @RequestParam q: String?,
        @RequestParam status: ReadingStatus?,
        @RequestParam featured: Boolean?,
        @RequestParam format: ReadingFormat?,
        @RequestParam sort: ReadingSort?,
        @RequestParam page: Int?,
        @RequestParam size: Int?,
    ): Page<ReadingCard> {
        val me = access.reader()
        requireLength(q, "q", 64)
        requireLength(topic, "topic", 120)
        if ((level?.size ?: 0) > 6) throw ValidationException("Invalid level", "level", "at most 6 levels")
        val query = CatalogQuery(language, level.orEmpty(), topic, minMinutes, maxMinutes, q, status, featured, sort ?: ReadingSort.RECENT, format = format)
        return catalog.search(me, query, PageRequest.of(page, size))
    }

    @GetMapping("/readings/{slug}")
    suspend fun detail(
        @PathVariable slug: String,
    ): ReadingDetail = catalog.detail(access.reader(), slug)

    // una versión publicada no cambia nunca (ADR-0003): se puede guardar en caché para siempre
    @GetMapping("/readings/{slug}/versions/{number}/content")
    suspend fun content(
        @PathVariable slug: String,
        @PathVariable number: Int,
    ): ResponseEntity<ReadingContentView> {
        access.reader()
        val ref = versions.find(slug, number) ?: throw NotFoundException("Reading version not found")
        val content = versions.content(ref.versionId)
        val view =
            ReadingContentView(
                ref.slug,
                ref.number,
                ref.language,
                ref.current,
                content.paragraphs.map { p ->
                    ContentParagraph(
                        p.id,
                        p.sentences.map { s -> ContentSentence(s.id, s.tokens.map { ContentToken(it.id, it.text, it.kind.name, it.space, it.entry) }) },
                    )
                },
                content.format.name,
                content.layout?.name,
                content.pages?.map { page ->
                    ContentPage(
                        page.id,
                        page.span,
                        page.layout.name,
                        page.paragraphs,
                        page.illustration?.let { ContentIllustration(IllustrationFiles.url(it.file), it.alt) },
                    )
                },
                ContentBlock.of(content),
            )
        return ResponseEntity
            .ok()
            .eTag("\"${ref.versionId}\"")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .body(view)
    }

    // traducciones escritas por una persona para una oración o un párrafo. Sin traducción: 404, nunca una inventada
    @GetMapping("/readings/{slug}/versions/{number}/translations")
    suspend fun translations(
        @PathVariable slug: String,
        @PathVariable number: Int,
        @RequestParam unit: String,
        @RequestParam ref: String?,
        @RequestParam language: String?,
    ): List<TranslationView> {
        val me = access.reader()
        if (unit !in setOf("SENTENCE", "PARAGRAPH")) throw ValidationException("Invalid unit", "unit", "must be SENTENCE or PARAGRAPH")
        requireLength(ref, "ref", 32)
        val into =
            language
                ?: profiles.find(me.organizationId, me.userId)?.interfaceLanguage
                ?: throw ValidationException("Missing language", "language", "required while there is no learner profile")
        val version = versions.find(slug, number) ?: throw NotFoundException("Reading version not found")
        val found: List<TranslationUnit> = versions.translations(version.versionId, unit, into, ref)
        if (ref != null && found.isEmpty()) throw NotFoundException("No translation for this $unit in $into")
        return found.map { TranslationView(it.unit, it.ref, it.language, it.text) }
    }
}

// lo público y cacheable del catálogo: el árbol de temas y las portadas
@RestController
@RequestMapping("/api/lesen/public")
class PublicCatalogController(
    private val catalog: CatalogService,
    private val media: Media,
) {
    @GetMapping("/topics")
    suspend fun topics(): ResponseEntity<List<TopicNode>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .body(catalog.topicTree())

    // las portadas: las de la demostración (SVG sin texto, del classpath) y las subidas desde el estudio (PNG, JPEG o WebP,
    // del almacén de archivos). Nunca se sirve un SVG subido: podría llevar código
    @GetMapping("/covers/{file}")
    suspend fun cover(
        @PathVariable file: String,
    ): ResponseEntity<Resource> {
        if (!COVER.matches(file)) throw NotFoundException("Cover not found")
        val type: String
        val resource: Resource
        if (file.endsWith(".svg")) {
            resource = ClassPathResource("demo/covers/$file").takeIf { it.exists() } ?: throw NotFoundException("Cover not found")
            type = "image/svg+xml"
        } else {
            val stored = media.find(file)?.takeIf { it.kind == MediaKind.COVER } ?: throw NotFoundException("Cover not found")
            resource = ByteArrayResource(media.bytes(stored) ?: throw NotFoundException("Cover not found"))
            type = stored.contentType
        }
        return ResponseEntity
            .ok()
            .contentType(MediaType.valueOf(type))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            .header("X-Content-Type-Options", "nosniff")
            .body(resource)
    }

    // las ilustraciones de los libros (ADR-0014): SVG de la demostración o imágenes del almacén. Una versión publicada no
    // cambia, y el nombre sale de la huella: se guardan en caché para siempre
    @GetMapping("/illustrations/{file}")
    suspend fun illustration(
        @PathVariable file: String,
    ): ResponseEntity<Resource> {
        if (!lesen.reading.PageRules.ILLUSTRATION_FILE
                .matches(file)
        ) {
            throw NotFoundException("Illustration not found")
        }
        val type: String
        val resource: Resource
        if (file.endsWith(".svg")) {
            resource = IllustrationFiles.demo(file).takeIf { it.exists() } ?: throw NotFoundException("Illustration not found")
            type = "image/svg+xml"
        } else {
            val stored = media.find(file)?.takeIf { it.kind == MediaKind.ILLUSTRATION } ?: throw NotFoundException("Illustration not found")
            resource = ByteArrayResource(media.bytes(stored) ?: throw NotFoundException("Illustration not found"))
            type = stored.contentType
        }
        return ResponseEntity
            .ok()
            .contentType(MediaType.valueOf(type))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            .header("X-Content-Type-Options", "nosniff")
            .body(resource)
    }

    companion object {
        private val COVER = Regex("^[a-z0-9]+(-[a-z0-9]+)*\\.(svg|png|jpg|webp)$")
    }
}
