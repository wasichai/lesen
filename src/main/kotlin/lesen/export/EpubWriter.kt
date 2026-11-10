package lesen.export

import lesen.export.XhtmlWriter.esc
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// empaqueta una lectura como ePub 3 (ADR-0014) con java.util.zip del JDK: lo abren Apple Books, Kindle (Send to Kindle
// acepta ePub) y cualquier lector. El contenedor exige que "mimetype" sea la primera entrada y vaya sin comprimir
object EpubWriter {
    const val MEDIA_TYPE = "application/epub+zip"

    fun write(doc: ExportDocument): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            stored(zip, "mimetype", MEDIA_TYPE.toByteArray(Charsets.US_ASCII))
            deflated(zip, "META-INF/container.xml", CONTAINER)
            deflated(zip, "OEBPS/content.opf", opf(doc))
            deflated(zip, "OEBPS/nav.xhtml", nav(doc))
            deflated(zip, "OEBPS/style.css", XhtmlWriter.CSS.trimStart())
            doc.cover?.let { cover ->
                deflated(zip, "OEBPS/images/cover.${cover.extension}", cover.bytes)
                deflated(zip, "OEBPS/cover.xhtml", XhtmlWriter.cover(doc, "images/cover.${cover.extension}"))
            }
            deflated(zip, "OEBPS/text.xhtml", XhtmlWriter.text(doc))
            if (doc.options.glossary) deflated(zip, "OEBPS/glossary.xhtml", XhtmlWriter.glossary(doc))
            if (doc.options.notes) deflated(zip, "OEBPS/notes.xhtml", XhtmlWriter.notes(doc))
        }
        return out.toByteArray()
    }

    fun opf(doc: ExportDocument): String {
        val modified = DateTimeFormatter.ISO_INSTANT.format(doc.generatedAt.truncatedTo(ChronoUnit.SECONDS))
        val manifest =
            buildString {
                append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""").append('\n')
                append("""    <item id="css" href="style.css" media-type="text/css"/>""").append('\n')
                doc.cover?.let {
                    append("""    <item id="cover-image" href="images/cover.${it.extension}" media-type="${esc(it.contentType)}" properties="cover-image"/>""")
                    append('\n')
                    append("""    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
                }
                XhtmlWriter.sections(doc).forEach { (file, _, _) ->
                    append("""    <item id="${file.substringBefore('.')}" href="$file" media-type="application/xhtml+xml"/>""").append('\n')
                }
            }
        val spine =
            buildString {
                if (doc.cover != null) append("""    <itemref idref="cover" linear="yes"/>""").append('\n')
                XhtmlWriter.sections(doc).forEach { (file, _, _) -> append("""    <itemref idref="${file.substringBefore('.')}"/>""").append('\n') }
            }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="${esc(doc.language)}">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">${esc(doc.identifier)}</dc:identifier>
    <dc:title>${esc(doc.title)}</dc:title>
    <dc:language>${esc(doc.language)}</dc:language>
    <dc:publisher>Lesen</dc:publisher>
    <dc:description>${esc(doc.summary)}</dc:description>
    <meta property="dcterms:modified">$modified</meta>
${if (doc.cover != null) "    <meta name=\"cover\" content=\"cover-image\"/>\n" else ""}  </metadata>
  <manifest>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>
"""
    }

    fun nav(doc: ExportDocument): String {
        val ui = esc(doc.labels.language)
        val items =
            XhtmlWriter.sections(doc).joinToString("\n") { (file, title, language) ->
                """      <li><a href="$file" lang="${esc(language)}" xml:lang="${esc(language)}">${esc(title)}</a></li>"""
            }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$ui" xml:lang="$ui">
<head>
<meta charset="utf-8"/>
<title>${esc(doc.labels.contents)}</title>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>${esc(doc.labels.contents)}</h1>
    <ol>
$items
    </ol>
  </nav>
</body>
</html>
"""
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    private fun stored(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val crc = CRC32().apply { update(bytes) }
        val entry =
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun deflated(
        zip: ZipOutputStream,
        name: String,
        text: String,
    ) = deflated(zip, name, text.toByteArray(Charsets.UTF_8))

    private fun deflated(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        zip.write(bytes)
        zip.closeEntry()
    }
}
