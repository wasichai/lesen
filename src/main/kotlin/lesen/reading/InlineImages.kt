package lesen.reading

import wasichai.core.common.FieldViolation

// una imagen entre párrafos de un texto (ADR-0018). after: cuántos párrafos la preceden (0, antes del primero). file:
// la ilustración subida o de la demostración (null: pendiente). width y height, en píxeles, para reservar el sitio.
// alt y caption, en el idioma de la lectura
data class DraftImage(
    val after: Int,
    val file: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val alt: String = "",
    val caption: String? = null,
)

// una imagen publicada: after es el id del párrafo que la precede, o null si va antes del primero
data class InlineImage(
    val id: String,
    val after: String?,
    val file: String,
    val width: Int,
    val height: Int,
    val alt: String,
    val caption: String?,
)

// las reglas de las imágenes entre párrafos que no dependen de la base (ADR-0018)
object InlineImages {
    const val MAX_IMAGES = 30
    const val MAX_ALT = 300
    const val MAX_CAPTION = 500

    fun problems(draft: ReadingDraft): List<FieldViolation> {
        if (draft.images.isEmpty()) return emptyList()
        val problems = mutableListOf<FieldViolation>()
        if (draft.format == ReadingFormat.PICTURE_BOOK) {
            problems += FieldViolation("images", "a picture book shows its illustrations on its pages")
            return problems
        }
        if (draft.images.size > MAX_IMAGES) problems += FieldViolation("images", "must have at most $MAX_IMAGES images")
        draft.images.forEachIndexed { i, image ->
            val field = "images[$i]"
            if (image.after !in 0..draft.paragraphs.size) problems += FieldViolation("$field.after", "must be between 0 and ${draft.paragraphs.size}")
            if (image.file != null && !PageRules.ILLUSTRATION_FILE.matches(image.file)) problems += FieldViolation("$field.file", "must be a file name")
            if (image.file != null && ((image.width ?: 0) <= 0 || (image.height ?: 0) <= 0)) {
                problems += FieldViolation("$field.width", "an image needs its width and height in pixels")
            }
            if (image.alt.length > MAX_ALT) problems += FieldViolation("$field.alt", "must be at most $MAX_ALT characters")
            if ((image.caption?.length ?: 0) > MAX_CAPTION) problems += FieldViolation("$field.caption", "must be at most $MAX_CAPTION characters")
        }
        return problems
    }

    // los índices de las imágenes que no se pueden publicar todavía: sin archivo o sin texto alternativo
    fun missingFiles(draft: ReadingDraft): List<Int> = draft.images.indices.filter { draft.images[it].file == null }

    fun missingAlt(draft: ReadingDraft): List<Int> = draft.images.indices.filter { draft.images[it].alt.isBlank() }

    // las imágenes publicadas, en orden de lectura (por su sitio; a igual sitio, en el orden del borrador). Una pendiente
    // no se publica (el publicador lo impide antes)
    fun resolve(
        draft: ReadingDraft,
        paragraphs: List<Paragraph>,
    ): List<InlineImage>? {
        if (draft.images.isEmpty()) return null
        return draft.images
            .withIndex()
            .sortedBy { it.value.after }
            .mapNotNull { (_, image) -> image.takeIf { it.file != null && it.width != null && it.height != null } }
            .mapIndexed { n, image ->
                InlineImage(
                    id = "img-${n + 1}",
                    after = paragraphs.getOrNull(image.after - 1)?.id,
                    file = image.file!!,
                    width = image.width!!,
                    height = image.height!!,
                    alt = image.alt.trim(),
                    caption = image.caption?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
    }

    // de vuelta al borrador, para volver a editarlo
    fun draftImages(
        images: List<InlineImage>,
        paragraphs: List<Paragraph>,
    ): List<DraftImage> =
        images.map { image ->
            DraftImage(
                after = image.after?.let { id -> paragraphs.indexOfFirst { it.id == id } + 1 } ?: 0,
                file = image.file,
                width = image.width,
                height = image.height,
                alt = image.alt,
                caption = image.caption,
            )
        }
}
