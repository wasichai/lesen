package lesen.editorial

import kotlinx.coroutines.reactor.awaitSingle
import lesen.common.Access
import lesen.common.MediaFile
import lesen.dictionary.LexiconEntry
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.util.UUID

// el estudio editorial (ADR-0012). Ver: READ sobre estudio; escribir y enviar a revisión: UPDATE; devolver y publicar:
// la acción propia PUBLICAR. Nunca se mira un rol
@RestController
@RequestMapping("/api/lesen/studio")
class StudioController(
    private val access: Access,
    private val service: StudioService,
) {
    @GetMapping("/drafts")
    suspend fun drafts(
        @RequestParam status: DraftStatus?,
    ): List<DraftSummary> {
        access.studio()
        return service.drafts(status)
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: NewDraft,
    ): DraftView = service.create(access.writeStudio(), request)

    @GetMapping("/drafts/{id}")
    suspend fun draft(
        @PathVariable id: UUID,
    ): DraftView {
        access.studio()
        return service.draft(id)
    }

    // el borrador entero (documento y preguntas) con la revisión que se editó: si otra persona guardó antes, 409
    @PutMapping("/drafts/{id}")
    suspend fun save(
        @PathVariable id: UUID,
        @RequestBody request: SaveDraft,
    ): DraftView = service.save(access.writeStudio(), id, request)

    @DeleteMapping("/drafts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun discard(
        @PathVariable id: UUID,
    ) = service.discard(access.writeStudio(), id)

    @GetMapping("/drafts/{id}/check")
    suspend fun check(
        @PathVariable id: UUID,
    ): CheckReport {
        access.studio()
        return service.check(id)
    }

    // multipart: audio (mp3, ogg o m4a), timings (WebVTT, SRT o el JSON de generate_audio.py), durationMs, voice, license
    @PostMapping("/drafts/{id}/audio")
    suspend fun audio(
        @PathVariable id: UUID,
        @RequestPart("audio") audio: FilePart,
        @RequestPart("timings") timings: FilePart,
        @RequestPart("durationMs") durationMs: String,
        @RequestPart("voice") voice: String,
        @RequestPart("license") license: String,
    ): DraftView {
        val me = access.writeStudio()
        val duration = durationMs.trim().toIntOrNull() ?: throw ValidationException("Invalid audio", "durationMs", "must be a number of milliseconds")
        return service.attachAudio(me, id, bytes(audio, AUDIO_LIMIT), String(bytes(timings, TIMINGS_LIMIT), Charsets.UTF_8), duration, voice, license)
    }

    @DeleteMapping("/drafts/{id}/audio")
    suspend fun removeAudio(
        @PathVariable id: UUID,
    ): DraftView = service.removeAudio(access.writeStudio(), id)

    @PostMapping("/drafts/{id}/submit")
    suspend fun submit(
        @PathVariable id: UUID,
        @RequestBody(required = false) note: StepNote?,
    ): DraftView = service.submit(access.writeStudio(), id, note ?: StepNote())

    @PostMapping("/drafts/{id}/return")
    suspend fun reject(
        @PathVariable id: UUID,
        @RequestBody note: StepNote,
    ): DraftView = service.reject(access.publish(), id, note)

    @PostMapping("/drafts/{id}/publish")
    suspend fun publish(
        @PathVariable id: UUID,
        @RequestBody(required = false) note: StepNote?,
    ): DraftView = service.publish(access.publish(), id, note ?: StepNote())

    // una portada (PNG, JPEG o WebP): devuelve el nombre de archivo que se pone en el borrador
    @PostMapping("/covers")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun cover(
        @RequestPart("file") file: FilePart,
    ): MediaFile = service.cover(access.writeStudio(), bytes(file, COVER_LIMIT))

    @GetMapping("/readings")
    suspend fun readings(): List<PublishedReading> {
        access.studio()
        return service.readings()
    }

    @GetMapping("/readings/{slug}/history")
    suspend fun history(
        @PathVariable slug: String,
    ): ReadingHistory {
        access.studio()
        return service.history(slug)
    }

    @PostMapping("/dictionary/entries")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun entry(
        @RequestBody request: EntryRequest,
    ): LexiconEntry {
        access.writeStudio()
        return service.addEntry(request)
    }

    // los bytes de una parte, con un tope: lo que pase de él no se lee entero en memoria
    private suspend fun bytes(
        part: FilePart,
        limit: Int,
    ): ByteArray {
        val joined =
            try {
                DataBufferUtils.join(part.content(), limit).awaitSingle()
            } catch (_: org.springframework.core.io.buffer.DataBufferLimitException) {
                throw ValidationException("File too large", part.name(), "at most ${limit / 1_048_576} MB")
            }
        return try {
            ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    companion object {
        const val AUDIO_LIMIT = 30 * 1_048_576
        const val COVER_LIMIT = 3 * 1_048_576
        const val TIMINGS_LIMIT = 2 * 1_048_576
    }
}
