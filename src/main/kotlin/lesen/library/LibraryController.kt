package lesen.library

import lesen.catalog.CatalogQuery
import lesen.catalog.CatalogService
import lesen.catalog.ReadingCard
import lesen.catalog.ReadingSort
import lesen.catalog.ReadingStatus
import lesen.common.Access
import lesen.common.ActivityType
import lesen.common.Learner
import lesen.common.LearningActivity
import lesen.common.LearningActivityListener
import lesen.common.PageRequest
import lesen.common.emit
import lesen.common.requireLength
import lesen.reading.ReadingCompletionListener
import lesen.reading.ReadingVersions
import lesen.reading.VersionRef
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ProgressView(
    val version: Int,
    val position: String?,
    val percent: Int,
    val secondsRead: Int,
    val completed: Boolean,
    val startedAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

data class CompletionRequest(
    val version: Int,
    val secondsRead: Int = 0,
)

data class NoteRequest(
    val version: Int,
    val sentenceRef: String,
    val note: String? = null,
)

data class Library(
    val inProgress: List<ReadingCard>,
    val bookmarked: List<ReadingCard>,
    val completed: List<ReadingCard>,
)

@Service
class LibraryService(
    private val store: LibraryStore,
    private val versions: ReadingVersions,
    private val catalog: CatalogService,
    private val clock: Clock,
    private val completionListeners: List<ReadingCompletionListener>,
    private val activity: List<LearningActivityListener>,
) {
    suspend fun progress(
        me: Learner,
        slug: String,
    ): ProgressView? {
        val reading = version(slug, null)
        return store.progress(me.organizationId, me.userId, reading.readingId)?.view()
    }

    suspend fun report(
        me: Learner,
        slug: String,
        report: ProgressReport,
    ): ProgressView {
        val version = version(slug, report.version)
        if (report.position != null) {
            requireLength(report.position, "position", 32)
            if (versions.content(version.versionId).sentence(report.position) == null) {
                throw ValidationException("Invalid position", "position", "must be a sentence of version ${report.version}")
            }
        }
        if (report.percent !in 0..100) throw ValidationException("Invalid percent", "percent", "must be between 0 and 100")
        val previous = store.progress(me.organizationId, me.userId, version.readingId)
        val merged = ProgressPolicy.merge(previous, report, clock.instant())
        store.saveProgress(me.organizationId, me.userId, version.readingId, merged)
        recordReading(me, version, previous, merged, 0)
        return merged.view()
    }

    suspend fun complete(
        me: Learner,
        slug: String,
        request: CompletionRequest,
    ): ProgressView {
        val version = version(slug, request.version)
        val previous = store.progress(me.organizationId, me.userId, version.readingId)
        val done = ProgressPolicy.complete(previous, request.version, request.secondsRead, clock.instant())
        store.saveProgress(me.organizationId, me.userId, version.readingId, done)
        completionListeners.forEach { it.completed(me.organizationId, me.userId, version) }
        val words =
            if (previous?.completedAt == null) versions.content(version.versionId).words.size else 0
        recordReading(me, version, previous, done, words)
        return done.view()
    }

    // lo que cuenta para el progreso: empezar (una vez), los segundos nuevos sobre el total guardado y terminar (una vez,
    // con las palabras de la versión). Repetir un informe no registra nada: no hay segundos nuevos
    private suspend fun recordReading(
        me: Learner,
        version: VersionRef,
        previous: ProgressState?,
        now: ProgressState,
        words: Int,
    ) {
        fun event(
            type: ActivityType,
            key: String?,
            seconds: Int = 0,
            words: Int = 0,
        ) = LearningActivity(me.organizationId, me.userId, type, now.updatedAt, version.language, key, version.readingId, seconds, words)
        if (previous == null) activity.emit(event(ActivityType.READING_STARTED, "started:${version.readingId}"))
        val seconds = now.secondsRead - (previous?.secondsRead ?: 0)
        if (seconds > 0) activity.emit(event(ActivityType.PROGRESS, null, seconds = seconds))
        if (previous?.completedAt == null && now.completedAt != null) {
            activity.emit(event(ActivityType.READING_COMPLETED, "completed:${version.readingId}", words = words))
        }
    }

    suspend fun bookmark(
        me: Learner,
        slug: String,
        on: Boolean,
    ) = store.bookmark(me.organizationId, me.userId, version(slug, null).readingId, on)

    suspend fun notes(
        me: Learner,
        slug: String,
    ): List<Note> = store.notes(me.organizationId, me.userId, version(slug, null).readingId)

    suspend fun addNote(
        me: Learner,
        slug: String,
        request: NoteRequest,
    ): Note {
        requireLength(request.note, "note", 2000)
        requireLength(request.sentenceRef, "sentenceRef", 32)
        val version = version(slug, request.version)
        // la cita sale del texto publicado, nunca del cuerpo de la petición
        val sentence =
            versions.content(version.versionId).sentence(request.sentenceRef)
                ?: throw ValidationException("Invalid sentence", "sentenceRef", "must be a sentence of version ${request.version}")
        if (store.countNotes(me.organizationId, me.userId, version.readingId) >= MAX_NOTES) {
            throw ValidationException("Too many notes", "note", "at most $MAX_NOTES notes per reading")
        }
        val id = store.addNote(me.organizationId, me.userId, version.readingId, version.number, sentence.id, sentence.text, request.note)
        return store.notes(me.organizationId, me.userId, version.readingId).first { it.id == id }
    }

    suspend fun deleteNote(
        me: Learner,
        id: UUID,
    ) {
        if (!store.deleteNote(me.organizationId, me.userId, id)) throw NotFoundException("Note not found")
    }

    suspend fun library(
        me: Learner,
        text: String?,
    ): Library {
        requireLength(text, "q", 64)

        suspend fun list(
            status: ReadingStatus,
            sort: ReadingSort,
        ) = catalog.search(me, CatalogQuery(text = text, status = status, sort = sort), PageRequest(0, PageRequest.MAX_SIZE)).content
        return Library(
            inProgress = list(ReadingStatus.IN_PROGRESS, ReadingSort.RECENT).sortedByDescending { it.progress?.updatedAt },
            bookmarked = list(ReadingStatus.BOOKMARKED, ReadingSort.TITLE),
            completed = list(ReadingStatus.COMPLETED, ReadingSort.RECENT).sortedByDescending { it.progress?.updatedAt },
        )
    }

    private suspend fun version(
        slug: String,
        number: Int?,
    ): VersionRef = versions.find(slug, number) ?: throw NotFoundException("Reading version not found")

    private fun ProgressState.view() = ProgressView(version, position, percent, secondsRead, completedAt != null, startedAt, updatedAt, completedAt)

    companion object {
        const val MAX_NOTES = 200
    }
}

@RestController
@RequestMapping("/api/lesen")
class LibraryController(
    private val access: Access,
    private val service: LibraryService,
) {
    @GetMapping("/library")
    suspend fun library(
        @RequestParam q: String?,
    ): Library = service.library(access.notebook(), q)

    // sin progreso todavía: 404, la lectura empieza desde el principio
    @GetMapping("/readings/{slug}/progress")
    suspend fun progress(
        @PathVariable slug: String,
    ): ProgressView = service.progress(access.notebook(), slug) ?: throw NotFoundException("No progress yet")

    // idempotente: repetir el mismo informe deja el mismo estado (ProgressPolicy)
    @PutMapping("/readings/{slug}/progress")
    suspend fun report(
        @PathVariable slug: String,
        @RequestBody report: ProgressReport,
    ): ProgressView = service.report(access.writeNotebook(), slug, report)

    @PostMapping("/readings/{slug}/completion")
    suspend fun complete(
        @PathVariable slug: String,
        @RequestBody request: CompletionRequest,
    ): ProgressView = service.complete(access.writeNotebook(), slug, request)

    @PutMapping("/readings/{slug}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun bookmark(
        @PathVariable slug: String,
    ) = service.bookmark(access.writeNotebook(), slug, true)

    @DeleteMapping("/readings/{slug}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unbookmark(
        @PathVariable slug: String,
    ) = service.bookmark(access.writeNotebook(), slug, false)

    @GetMapping("/readings/{slug}/notes")
    suspend fun notes(
        @PathVariable slug: String,
    ): List<Note> = service.notes(access.notebook(), slug)

    @PostMapping("/readings/{slug}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun addNote(
        @PathVariable slug: String,
        @RequestBody request: NoteRequest,
    ): Note = service.addNote(access.writeNotebook(), slug, request)

    @DeleteMapping("/notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteNote(
        @PathVariable id: UUID,
    ) = service.deleteNote(access.writeNotebook(), id)
}
