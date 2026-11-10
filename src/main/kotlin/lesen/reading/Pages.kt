package lesen.reading

import wasichai.core.common.FieldViolation

// el formato de una lectura (ADR-0014): el texto de siempre o un libro ilustrado, repartido en páginas con maqueta
enum class ReadingFormat { TEXT, PICTURE_BOOK }

// el catálogo cerrado de maquetas de un libro ilustrado (ADR-0014). spans: si sirve para una página (1), para una doble
// página (2) o para las dos. illustrated: lleva ilustración. textless: la página va sin texto
enum class PageLayout(
    val spans: Set<Int>,
    val illustrated: Boolean,
    val textless: Boolean = false,
) {
    // la ilustración arriba y el texto debajo
    IMAGE_TOP(setOf(1), true),

    // el texto arriba y la ilustración debajo
    IMAGE_BOTTOM(setOf(1), true),

    // la ilustración a sangre y el texto encima, en una banda que garantiza el contraste
    TEXT_OVER_IMAGE(setOf(1, 2), true),

    // una viñeta pequeña entre el texto
    VIGNETTE(setOf(1), true),

    // solo texto
    TEXT_ONLY(setOf(1), false),

    // solo la ilustración: una pausa sin texto
    IMAGE_ONLY(setOf(1, 2), true, textless = true),

    // doble página: la ilustración en la página izquierda y el texto en la derecha
    SPREAD_IMAGE_LEFT(setOf(2), true),

    // doble página: el texto en la izquierda y la ilustración en la derecha
    SPREAD_IMAGE_RIGHT(setOf(2), true),

    // doble página: una ilustración que cruza las dos páginas, con una franja de texto
    PANORAMA(setOf(2), true),
}

// una página del borrador: cuántos párrafos consecutivos lleva, su ancho (1 o 2 páginas) y su maqueta, si no es la del
// libro. Los párrafos siguen siendo la unidad del texto: sus ids no cambian por estar en un libro (ADR-0004)
data class DraftPage(
    val span: Int = 1,
    val layout: PageLayout? = null,
    val paragraphs: Int,
    val illustration: DraftIllustration? = null,
)

// file: el archivo subido o generado (null: pendiente). alt: el texto alternativo por idioma de interfaz. scene: lo que
// pasa en la página, para quien ilustra; no se publica
data class DraftIllustration(
    val file: String? = null,
    val alt: Map<String, String> = emptyMap(),
    val scene: String? = null,
)

// una página publicada: su maqueta ya resuelta y los ids de sus párrafos
data class Page(
    val id: String,
    val span: Int,
    val layout: PageLayout,
    val paragraphs: List<String>,
    val illustration: Illustration? = null,
)

data class Illustration(
    val file: String,
    val alt: Map<String, String>,
)

// las reglas de un libro ilustrado que no dependen de la base (ADR-0014)
object PageRules {
    const val MAX_PAGES = 64
    val ILLUSTRATION_FILE = Regex("^[a-z0-9]+(-[a-z0-9]+)*\\.(svg|png|jpg|webp)$")

    fun effective(
        draft: ReadingDraft,
        page: DraftPage,
    ): PageLayout? = page.layout ?: draft.layout

    fun problems(draft: ReadingDraft): List<FieldViolation> {
        val problems = mutableListOf<FieldViolation>()
        if (draft.format == ReadingFormat.TEXT) {
            if (draft.pages.isNotEmpty()) problems += FieldViolation("pages", "a text reading has no pages")
            if (draft.layout != null) problems += FieldViolation("layout", "a text reading has no layout")
            return problems
        }
        if (draft.layout == null) problems += FieldViolation("layout", "a picture book needs a default layout")
        if (draft.pages.isEmpty()) problems += FieldViolation("pages", "a picture book needs at least one page")
        if (draft.pages.size > MAX_PAGES) problems += FieldViolation("pages", "must have at most $MAX_PAGES pages")
        val total = draft.pages.sumOf { it.paragraphs.coerceAtLeast(0) }
        if (draft.pages.isNotEmpty() && total != draft.paragraphs.size) {
            problems += FieldViolation("pages", "the pages hold $total paragraphs, the text has ${draft.paragraphs.size}")
        }
        draft.pages.forEachIndexed { i, page ->
            val field = "pages[$i]"
            if (page.span !in 1..2) problems += FieldViolation("$field.span", "must be 1 (a page) or 2 (a spread)")
            if (page.paragraphs < 0) problems += FieldViolation("$field.paragraphs", "must not be negative")
            val layout = effective(draft, page) ?: return@forEachIndexed
            if (page.span !in layout.spans) problems += FieldViolation("$field.layout", "$layout does not fit a span of ${page.span}")
            if (layout.textless && page.paragraphs > 0) problems += FieldViolation("$field.paragraphs", "$layout has no text")
            if (!layout.textless && page.paragraphs == 0) problems += FieldViolation("$field.paragraphs", "$layout needs text")
            val illustration = page.illustration
            if (illustration?.file != null && !ILLUSTRATION_FILE.matches(illustration.file)) {
                problems += FieldViolation("$field.illustration.file", "must be a file name")
            }
            illustration?.alt?.forEach { (language, text) ->
                if (text.isBlank() ||
                    text.length > 300
                ) {
                    problems += FieldViolation("$field.illustration.alt.$language", "must have between 1 and 300 characters")
                }
            }
            if ((illustration?.scene?.length ?: 0) > 1000) problems += FieldViolation("$field.illustration.scene", "must be at most 1000 characters")
        }
        return problems
    }

    // los índices de las páginas cuya maqueta pide una ilustración que todavía no tiene archivo
    fun missingIllustrations(draft: ReadingDraft): List<Int> =
        if (draft.format != ReadingFormat.PICTURE_BOOK) {
            emptyList()
        } else {
            draft.pages.indices.filter { i ->
                val page = draft.pages[i]
                effective(draft, page)?.illustrated == true && page.illustration?.file == null
            }
        }

    // las páginas publicadas: g1, g2…, cada una con los ids de sus párrafos y la maqueta efectiva. Una ilustración sin
    // archivo no se publica (el publicador lo impide antes); aquí queda fuera
    fun resolve(
        draft: ReadingDraft,
        paragraphs: List<Paragraph>,
    ): List<Page>? {
        if (draft.format == ReadingFormat.TEXT) return null
        var next = 0
        return draft.pages.mapIndexed { i, page ->
            val ids = paragraphs.subList(next, next + page.paragraphs).map { it.id }
            next += page.paragraphs
            val layout = effective(draft, page)!!
            val illustration =
                page.illustration
                    ?.takeIf { layout.illustrated }
                    ?.file
                    ?.let { Illustration(it, page.illustration.alt.mapValues { (_, text) -> text.trim() }) }
            Page("g${i + 1}", page.span, layout, ids, illustration)
        }
    }

    // un libro desde sus páginas publicadas, para volver a editarlo: la maqueta que coincide con la del libro no se repite
    fun draftPages(
        layout: PageLayout?,
        pages: List<Page>,
    ): List<DraftPage> =
        pages.map { page ->
            DraftPage(
                span = page.span,
                layout = page.layout.takeIf { it != layout },
                paragraphs = page.paragraphs.size,
                illustration = page.illustration?.let { DraftIllustration(it.file, it.alt) },
            )
        }
}
