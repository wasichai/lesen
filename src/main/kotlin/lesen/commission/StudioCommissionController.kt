package lesen.commission

import lesen.common.Access
import lesen.common.Jsonb
import lesen.common.Media
import lesen.common.MediaKind
import lesen.editorial.DraftView
import lesen.editorial.StudioService
import lesen.illustration.Art
import lesen.illustration.ArtBible
import lesen.illustration.BriefPage
import lesen.illustration.IllustrationBrief
import lesen.illustration.IllustrationPipeline
import lesen.illustration.IllustrationSteps
import lesen.illustration.ImageProvider
import lesen.illustration.Picture
import lesen.illustration.ReferenceSet
import lesen.language.Languages
import lesen.reading.IllustrationFiles
import lesen.reading.PageRules
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

// un encargo tal como lo ve quien lo pidió
data class CommissionView(
    val id: UUID,
    val target: JobTarget,
    val status: JobStatus,
    val request: CommissionRequest,
    val draftId: UUID?,
    val readingSlug: String?,
    val reason: String?,
    val findings: List<Map<String, String?>>,
    val directedBy: String?,
    val illustrations: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(job: StoredJob) =
            CommissionView(
                job.id,
                job.target,
                job.status,
                job.request,
                job.draftId,
                job.readingSlug,
                job.reason,
                job.findings,
                job.directedBy,
                job.usage["illustrations"],
                job.createdAt,
                job.updatedAt,
            )
    }
}

data class CommissionStatus(
    val available: Boolean,
    val illustrations: Boolean,
)

// el estudio encarga lecturas y libros ilustrados (ADR-0015): el encargo corre en segundo plano y termina en un borrador
// que sigue la revisión de siempre. Escribir en el estudio basta para encargar; ver un encargo, leer el estudio de la
// misma organización
@RestController
@RequestMapping("/api/lesen/studio")
class StudioCommissionController(
    private val access: Access,
    private val service: CommissionService,
    private val store: CommissionStore,
    private val studio: StudioService,
    private val provider: ImageProvider,
    private val media: Media,
    private val illustrations: IllustrationFiles,
    private val languages: Languages,
    private val properties: lesen.common.LesenProperties,
) {
    @GetMapping("/generations/status")
    suspend fun status(): CommissionStatus {
        access.studio()
        return CommissionStatus(service.available, provider.available)
    }

    @PostMapping("/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun create(
        @RequestBody request: CommissionRequest,
    ): CommissionView {
        val me = access.writeStudio()
        return CommissionView.of(service.enqueue(me.organizationId, me.userId, me.user.email, JobTarget.STUDIO, request))
    }

    @GetMapping("/generations")
    suspend fun mine(): List<CommissionView> {
        val me = access.studio()
        return store.mine(me.organizationId, me.userId, JobTarget.STUDIO).map(CommissionView::of)
    }

    @GetMapping("/generations/{id}")
    suspend fun one(
        @PathVariable id: UUID,
    ): CommissionView {
        val me = access.studio()
        val job =
            store.find(id)?.takeIf { it.target == JobTarget.STUDIO && it.organizationId == me.organizationId }
                ?: throw NotFoundException("Generation not found")
        return CommissionView.of(job)
    }

    // volver a dibujar la ilustración de una página con la biblia del libro, las hojas de sus personajes y la ilustración
    // de la página anterior como referencia. El texto alternativo se conserva
    @PostMapping("/drafts/{id}/pages/{page}/illustration")
    suspend fun redraw(
        @PathVariable id: UUID,
        @PathVariable page: Int,
    ): DraftView {
        val me = access.writeStudio()
        if (!provider.available) throw GenerationUnavailableException("No image provider is configured on this server")
        val (document, revision) = studio.editable(id)
        val target = document.pages.getOrNull(page - 1) ?: throw NotFoundException("Page not found")
        val layout = PageRules.effective(document, target)
        if (layout == null || !layout.illustrated) throw ValidationException("Invalid page", "page", "this page has no illustration")
        val scene = target.illustration?.scene?.takeIf { it.isNotBlank() } ?: throw ConflictException("Write the scene of this page before drawing it")
        val bible =
            store.byDraft(id)?.bible?.let { Jsonb.read<ArtBible>(it) }
                ?: ArtBible(document.title, "", "soft, even daylight", "", emptyList())
        val previous =
            document.pages
                .take(page - 1)
                .mapNotNull { it.illustration?.file }
                .lastOrNull()
                ?.let { illustrations.read(it) }
                ?.takeIf { it.contentType != "image/svg+xml" }
                ?.let { Picture(it.contentType, it.bytes) }
        val pipeline = IllustrationPipeline(provider, properties.illustration.maxRetries)
        val references = pipeline.references(bible)
        val brief =
            IllustrationBrief(
                document.title,
                document.language,
                document.level,
                Art(bible.style, bible.palette, bible.setting, bible.characters),
                listOf(
                    BriefPage(
                        page - 1,
                        layout,
                        target.span,
                        scene,
                        bible.characters.map { it.name }.filter { scene.contains(it, ignoreCase = true) },
                        "",
                        target.illustration.alt,
                    ),
                ),
                languages.all().filter { it.interfaceLanguage }.map { it.code },
            )
        val shots = IllustrationSteps.shotsFrom(brief)
        val drawn =
            pipeline
                .render(brief, bible, shots, ReferenceSet(references.characters, previous ?: references.style), null, "redraw")
                .pages
                .first()
        val picture = drawn.picture ?: throw GenerationUnavailableException("The illustration could not be drawn: ${drawn.problem}")
        val file = media.save(MediaKind.ILLUSTRATION, picture.bytes, me.user.email).file
        val pages = document.pages.mapIndexed { i, p -> if (i == page - 1) p.copy(illustration = p.illustration?.copy(file = file)) else p }
        return studio.replaceDocument(me, id, revision, document.copy(pages = pages))
    }
}
