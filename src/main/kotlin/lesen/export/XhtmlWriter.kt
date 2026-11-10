package lesen.export

import java.util.Base64

// escribe una lectura como XHTML: por partes para el ePub (texto, glosario, notas) o en una sola página para imprimir o
// guardar como PDF desde el navegador. Todo texto se escapa; el del idioma de estudio lleva su lang y el de la interfaz el
// suyo, para que los lectores de pantalla y la separación silábica acierten
object XhtmlWriter {
    const val CSS: String =
        """
body { font-family: Georgia, "Times New Roman", serif; line-height: 1.6; margin: 0 5%; color: #1c1b1a; background: #ffffff; }
h1 { font-size: 1.8em; line-height: 1.2; margin: 1.2em 0 0.2em; }
h2 { font-size: 1.3em; margin: 1.6em 0 0.6em; }
.subtitle { font-style: italic; margin: 0 0 0.6em; }
.meta { font-family: system-ui, sans-serif; font-size: 0.85em; color: #4a4744; }
.cover { text-align: center; margin: 1em 0; }
.cover img { max-width: 100%; max-height: 60vh; }
p.text { margin: 0 0 0.4em; text-align: justify; hyphens: auto; }
p.translation { margin: 0 0 1.2em; padding-left: 0.8em; border-left: 3px solid #b7b2ab; color: #3d3a37; font-size: 0.92em; }
p.missing { font-style: italic; }
dl.glossary dt { font-weight: bold; margin-top: 0.6em; }
dl.glossary dd { margin: 0 0 0 1.2em; }
.pos { font-weight: normal; font-style: italic; color: #4a4744; }
blockquote { margin: 1em 0 0.3em; padding-left: 0.8em; border-left: 3px solid #b7b2ab; font-style: italic; }
p.note { margin: 0 0 1em 1em; }
figure.illustration { margin: 1.2em 0 0.8em; text-align: center; }
figure.illustration img { max-width: 100%; max-height: 70vh; }
section.page.wide figure.illustration img { width: 100%; max-height: none; }
figure.inline { margin: 1.2em 0; text-align: center; }
figure.inline img { max-width: 100%; height: auto; }
figure.inline figcaption { font-size: 0.9em; font-style: italic; margin-top: 0.4em; }
p.missing-illustration { font-family: system-ui, sans-serif; font-size: 0.85em; color: #4a4744; font-style: italic; }
footer { margin: 2.5em 0 1em; font-family: system-ui, sans-serif; font-size: 0.8em; color: #4a4744; }
@media print {
  body { margin: 0; font-size: 11pt; }
  h2 { break-before: page; }
  h2.first { break-before: auto; }
  section.page { break-inside: avoid; }
  section.page.wide { break-before: page; }
  p.text, p.translation, dl.glossary dt, dl.glossary dd, blockquote, p.note { break-inside: avoid; }
}
"""

    // el texto de la lectura, con la traducción bajo cada párrafo si se pidió
    fun text(doc: ExportDocument): String = page(doc, doc.title, doc.language, readingBody(doc, coverSrc = null) { illustrationPath(it) } + footer(doc))

    // dónde va cada ilustración dentro del ePub
    fun illustrationPath(image: ExportImage) = "illustrations/${image.file}"

    private fun dataUri(image: ExportImage) = "data:${image.contentType};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

    fun glossary(doc: ExportDocument): String = page(doc, doc.labels.glossary, doc.labels.language, glossaryBody(doc, first = true))

    fun notes(doc: ExportDocument): String = page(doc, doc.labels.notes, doc.labels.language, notesBody(doc, first = true))

    fun cover(
        doc: ExportDocument,
        src: String,
    ): String = page(doc, doc.labels.cover, doc.labels.language, """<div class="cover"><img src="${esc(src)}" alt="${esc(doc.title)}"/></div>""")

    // una sola página con todo, para imprimir o guardar como PDF: la portada va incrustada para no pedir nada más
    fun printable(doc: ExportDocument): String {
        val body =
            buildString {
                append(readingBody(doc, doc.cover?.let(::dataUri), ::dataUri))
                if (doc.options.glossary) append(glossaryBody(doc, first = false))
                if (doc.options.notes) append(notesBody(doc, first = false))
                append(footer(doc))
            }
        return """<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="${esc(doc.language)}" xml:lang="${esc(doc.language)}">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>${esc(doc.title)}</title>
<style>$CSS</style>
</head>
<body>
$body
</body>
</html>
"""
    }

    // las secciones que tiene el documento, en orden: (archivo del ePub, título, idioma)
    fun sections(doc: ExportDocument): List<Triple<String, String, String>> =
        buildList {
            add(Triple("text.xhtml", doc.title, doc.language))
            if (doc.options.glossary) add(Triple("glossary.xhtml", doc.labels.glossary, doc.labels.language))
            if (doc.options.notes) add(Triple("notes.xhtml", doc.labels.notes, doc.labels.language))
        }

    private fun page(
        doc: ExportDocument,
        title: String,
        language: String,
        body: String,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${esc(language)}" xml:lang="${esc(language)}">
<head>
<meta charset="utf-8"/>
<title>${esc(title)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
$body
</body>
</html>
"""

    private fun readingBody(
        doc: ExportDocument,
        coverSrc: String?,
        imageSrc: (ExportImage) -> String,
    ): String =
        buildString {
            val study = esc(doc.language)
            val ui = esc(doc.labels.language)
            append("<section lang=\"$study\" xml:lang=\"$study\">\n")
            if (coverSrc != null) append("""<div class="cover"><img src="${esc(coverSrc)}" alt="${esc(doc.title)}"/></div>""").append('\n')
            append("<h1>${esc(doc.title)}</h1>\n")
            doc.subtitle?.takeIf { it.isNotBlank() }?.let { append("<p class=\"subtitle\">${esc(it)}</p>\n") }
            append("<p class=\"meta\" lang=\"$ui\" xml:lang=\"$ui\">${esc(doc.labels.level)} ${esc(doc.level)}</p>\n")
            val pages = doc.pages
            if (pages == null) {
                // un texto, con sus imágenes entre párrafos en su sitio (ADR-0018): alt y pie en el idioma de la lectura
                val inline = doc.inlineImages.groupBy { it.after }
                inline[null].orEmpty().forEach { append(figure(doc, it, imageSrc)) }
                doc.paragraphs.forEach { p ->
                    append(paragraph(doc, p))
                    inline[p.id].orEmpty().forEach { append(figure(doc, it, imageSrc)) }
                }
            } else {
                // un libro ilustrado (ADR-0014): cada página con su ilustración y sus párrafos, en el orden de lectura.
                // La maqueta exacta no se reproduce; una doble página lleva la imagen a todo el ancho
                pages.forEach { page ->
                    append("<section class=\"page${if (page.wide) " wide" else ""}\" id=\"${esc(page.id)}\">\n")
                    page.illustration?.let {
                        append(
                            "<figure class=\"illustration\"><img src=\"${esc(
                                imageSrc(it),
                            )}\" alt=\"${esc(page.alt)}\" lang=\"$ui\" xml:lang=\"$ui\"/></figure>\n",
                        )
                    }
                    if (page.missing) append("<p class=\"missing-illustration\" lang=\"$ui\" xml:lang=\"$ui\">${esc(doc.labels.illustrationMissing)}</p>\n")
                    page.paragraphs.forEach { append(paragraph(doc, it)) }
                    append("</section>\n")
                }
            }
            append("</section>\n")
        }

    private fun figure(
        doc: ExportDocument,
        image: ExportInline,
        imageSrc: (ExportImage) -> String,
    ): String =
        buildString {
            val ui = esc(doc.labels.language)
            append("<figure class=\"inline\" id=\"${esc(image.id)}\">")
            if (image.image != null) {
                append("<img src=\"${esc(imageSrc(image.image))}\" alt=\"${esc(image.alt)}\" width=\"${image.width}\" height=\"${image.height}\"/>")
            } else {
                append("<p class=\"missing-illustration\" lang=\"$ui\" xml:lang=\"$ui\">${esc(doc.labels.imageMissing)}</p>")
            }
            image.caption?.let { append("<figcaption>${esc(it)}</figcaption>") }
            append("</figure>\n")
        }

    private fun paragraph(
        doc: ExportDocument,
        p: ExportParagraph,
    ): String =
        buildString {
            val ui = esc(doc.labels.language)
            append("<p class=\"text\" id=\"${esc(p.id)}\">${esc(p.text)}</p>\n")
            if (doc.options.translations) {
                if (p.translation != null) {
                    append("<p class=\"translation\" lang=\"$ui\" xml:lang=\"$ui\">${esc(p.translation)}</p>\n")
                } else {
                    append("<p class=\"translation missing\" lang=\"$ui\" xml:lang=\"$ui\">${esc(doc.labels.noTranslation)}</p>\n")
                }
            }
        }

    private fun glossaryBody(
        doc: ExportDocument,
        first: Boolean,
    ): String =
        buildString {
            val ui = esc(doc.labels.language)
            val study = esc(doc.language)
            append("<section lang=\"$ui\" xml:lang=\"$ui\">\n<h2${if (first) " class=\"first\"" else ""}>${esc(doc.labels.glossary)}</h2>\n")
            if (doc.glossary.isEmpty()) {
                append("<p>${esc(doc.labels.glossaryEmpty)}</p>\n")
            } else {
                append("<dl class=\"glossary\">\n")
                doc.glossary.forEach { item ->
                    append(
                        "<dt><span lang=\"$study\" xml:lang=\"$study\">${esc(
                            item.lemma,
                        )}</span> <span class=\"pos\">${esc(item.pos.lowercase())}</span></dt>\n",
                    )
                    append("<dd>${esc(item.translations.joinToString(", ").ifBlank { "—" })}</dd>\n")
                }
                append("</dl>\n")
            }
            append("</section>\n")
        }

    private fun notesBody(
        doc: ExportDocument,
        first: Boolean,
    ): String =
        buildString {
            val ui = esc(doc.labels.language)
            val study = esc(doc.language)
            append("<section lang=\"$ui\" xml:lang=\"$ui\">\n<h2${if (first) " class=\"first\"" else ""}>${esc(doc.labels.notes)}</h2>\n")
            if (doc.notes.isEmpty()) append("<p>${esc(doc.labels.notesEmpty)}</p>\n")
            doc.notes.forEach { n ->
                append("<blockquote lang=\"$study\" xml:lang=\"$study\"><p>${esc(n.quote)}</p></blockquote>\n")
                n.note?.takeIf { it.isNotBlank() }?.let { append("<p class=\"note\">${esc(it)}</p>\n") }
            }
            append("</section>\n")
        }

    private fun footer(doc: ExportDocument): String {
        val ui = esc(doc.labels.language)
        return "<footer lang=\"$ui\" xml:lang=\"$ui\"><p>${esc(doc.labels.source)} · ${esc(doc.title)} · v${doc.version}</p></footer>\n"
    }

    fun esc(text: String): String =
        buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    // XML 1.0 no admite los caracteres de control salvo tabulador y saltos de línea
                    in '\u0000'..'\u0008', '\u000B', '\u000C', in '\u000E'..'\u001F' -> Unit
                    else -> append(c)
                }
            }
        }
}
