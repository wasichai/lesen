package lesen.audio

import lesen.common.Access
import lesen.common.Media
import lesen.common.MediaKind
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import java.time.Duration

@RestController
@RequestMapping("/api/lesen/readings")
class AudioController(
    private val access: Access,
    private val audio: AudioService,
) {
    // el audio de una versión no cambia nunca (ADR-0003 y ADR-0008): caché para siempre, como su texto
    @GetMapping("/{slug}/versions/{number}/audio")
    suspend fun audio(
        @PathVariable slug: String,
        @PathVariable number: Int,
    ): ResponseEntity<VersionAudio> {
        access.reader()
        val body = audio.forVersion(slug, number)
        return ResponseEntity
            .ok()
            .eTag("\"${body.assets.joinToString("-") { it.id.toString() }}\"")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .body(body)
    }
}

// los archivos de audio, públicos como las portadas: los de la demostración, del classpath; los del estudio, del almacén
// de archivos (lesen.media). Con rangos (206) para que el navegador pueda saltar a cualquier punto sin descargar el archivo
// entero: los atiende el escritor de Resource de WebFlux
@RestController
@RequestMapping("/api/lesen/public/audio")
class PublicAudioController(
    private val media: Media,
) {
    @GetMapping("/{file}")
    suspend fun file(
        @PathVariable file: String,
    ): ResponseEntity<Resource> {
        if (!FILE.matches(file)) throw NotFoundException("Audio not found")
        val demo = ClassPathResource("demo/audio/$file")
        val resource: Resource =
            if (demo.exists()) {
                demo
            } else {
                val stored = media.find(file)?.takeIf { it.kind == MediaKind.AUDIO } ?: throw NotFoundException("Audio not found")
                ByteArrayResource(media.bytes(stored) ?: throw NotFoundException("Audio not found"))
            }
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(TYPES.getValue(file.substringAfterLast('.'))))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
            .header("X-Content-Type-Options", "nosniff")
            .body(resource)
    }

    companion object {
        private val FILE = Regex("^[a-z0-9]+(-[a-z0-9]+)*\\.(mp3|ogg|m4a)$")
        private val TYPES = mapOf("mp3" to "audio/mpeg", "ogg" to "audio/ogg", "m4a" to "audio/mp4")
    }
}
