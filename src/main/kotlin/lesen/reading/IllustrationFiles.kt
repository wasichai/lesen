package lesen.reading

import lesen.common.Media
import lesen.common.MediaKind
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

// dónde vive la ilustración de una página (ADR-0014): las de la demostración son SVG sin texto del classpath, como sus
// portadas; las del estudio, imágenes subidas o generadas en el almacén de archivos. Nunca se acepta un SVG subido
@Component
class IllustrationFiles(
    private val media: Media,
) {
    suspend fun exists(file: String): Boolean =
        when {
            !PageRules.ILLUSTRATION_FILE.matches(file) -> false
            file.endsWith(".svg") -> demo(file).exists()
            else -> media.find(file)?.kind == MediaKind.ILLUSTRATION
        }

    companion object {
        const val URL = "/api/lesen/public/illustrations"

        fun demo(file: String) = ClassPathResource("demo/illustrations/$file")

        fun url(file: String) = "$URL/$file"
    }
}
