package lesen.commission

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lesen.assessment.Question
import lesen.common.Jsonb
import lesen.common.LesenProperties
import lesen.common.Media
import lesen.common.MediaKind
import lesen.editorial.StudioService
import lesen.generation.GeneratedReading
import lesen.generation.GenerationContext
import lesen.generation.GenerationRequest
import lesen.generation.Generator
import lesen.generation.Outcome
import lesen.generation.TextKind
import lesen.illustration.Art
import lesen.illustration.BriefPage
import lesen.illustration.CharacterSheet
import lesen.illustration.IllustratedBook
import lesen.illustration.IllustrationBrief
import lesen.illustration.Illustrator
import lesen.language.Languages
import lesen.reading.FormResolvers
import lesen.reading.PageRules
import lesen.reading.ReadingDraft
import lesen.reading.ReadingFormat
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import java.text.Normalizer
import java.util.UUID

// lo que se encarga: los controles guiados y, si se quiere, un prompt libre (ADR-0013), y el formato (ADR-0014)
data class CommissionRequest(
    val language: String,
    val level: String,
    val minutes: Int = 2,
    val kind: TextKind = TextKind.STORY,
    val format: ReadingFormat = ReadingFormat.TEXT,
    val topics: List<String> = emptyList(),
    val prompt: String? = null,
)

// sin clave no hay quien escriba: un servicio de fuera que falta es un 503, nunca un 500
class GenerationUnavailableException(
    message: String,
) : WasichaiException(HttpStatus.SERVICE_UNAVAILABLE, message)

// lo que sabe el perfil de escritura de la persona; en el estudio, nada (una lectura para todos)
data class LearnerKnowledge(
    val targetWords: List<lesen.generation.TargetWord> = emptyList(),
    val knownLemmas: List<String> = emptyList(),
    val knownEntries: Set<String> = emptySet(),
    val recentTitles: List<String> = emptyList(),
    val weakQuestionTypes: List<lesen.assessment.QuestionType> = emptyList(),
)

// donde termina un encargo: el estudio (un borrador) o una lectura a medida (ADR-0016). Lo que sabe de quien encarga
// entra en el perfil de escritura; en el estudio, nada (una lectura para todos)
interface CommissionSink {
    val target: JobTarget

    suspend fun knowledge(job: StoredJob): LearnerKnowledge = LearnerKnowledge()

    suspend fun deliver(
        job: StoredJob,
        draft: ReadingDraft,
        questions: List<Question>,
    ): Delivered
}

// el estudio: un borrador en DRAFT que pasa por la revisión de siempre (ADR-0012)
@Component
class StudioSink(
    private val studio: StudioService,
) : CommissionSink {
    override val target = JobTarget.STUDIO

    override suspend fun deliver(
        job: StoredJob,
        draft: ReadingDraft,
        questions: List<Question>,
    ) = Delivered(draftId = studio.createGenerated(job.actor, draft, questions, "${job.request.format} ${job.request.level}"))
}

data class Delivered(
    val draftId: UUID? = null,
    val readingSlug: String? = null,
)

// los encargos (ADR-0015): validar, encolar y hacer cada uno: escribir con el Generator de ADR-0013, ilustrar con el
// Illustrator si es un libro, guardar las ilustraciones y entregarlo. Todo fallo queda en el encargo con su motivo
@Service
class CommissionService(
    private val store: CommissionStore,
    private val generator: Generator,
    private val illustrator: Illustrator,
    private val languages: Languages,
    private val resolvers: FormResolvers,
    private val media: Media,
    sinks: List<CommissionSink>,
) {
    private val log = LoggerFactory.getLogger(CommissionService::class.java)
    private val sinks = sinks.associateBy { it.target }

    val available get() = generator.available

    suspend fun validate(request: CommissionRequest): CommissionRequest {
        val problems = mutableListOf<FieldViolation>()
        val study = languages.all().filter { it.study }.map { it.code }
        if (request.language !in study) problems += FieldViolation("language", "must be a language that can be studied")
        if (store.wordsPerMinute(request.level) == null) problems += FieldViolation("level", "must be a CEFR level A1–C2")
        if (request.minutes !in GenerationContext.MINUTES) problems += FieldViolation("minutes", "must be one of ${GenerationContext.MINUTES}")
        val prompt = request.prompt?.trim()?.takeIf { it.isNotEmpty() }
        if ((prompt?.length ?: 0) >
            GenerationContext.MAX_PROMPT
        ) {
            problems += FieldViolation("prompt", "must be at most ${GenerationContext.MAX_PROMPT} characters")
        }
        val topics = store.topics().toSet()
        if (request.topics.size > 3 || request.topics.any { it !in topics }) problems += FieldViolation("topics", "at most three topics of the installation")
        if (problems.isNotEmpty()) throw ValidationException("Invalid request", problems)
        return request.copy(prompt = prompt, topics = request.topics.distinct())
    }

    suspend fun enqueue(
        organizationId: UUID,
        userId: UUID,
        actor: String,
        target: JobTarget,
        request: CommissionRequest,
    ): StoredJob {
        if (!generator.available) throw GenerationUnavailableException("Reading generation is not configured on this server (set ANTHROPIC_API_KEY)")
        val valid = validate(request)
        return store.find(store.create(organizationId, userId, actor, target, valid))!!
    }

    // el perfil de escritura del encargo: el código lo calcula (ADR-0013); la persona solo aporta lo que ya sabe
    suspend fun context(
        request: CommissionRequest,
        knowledge: LearnerKnowledge = LearnerKnowledge(),
    ): GenerationContext {
        val interfaceLanguages = languages.all().filter { it.interfaceLanguage }.map { it.code }
        return GenerationContext(
            request = GenerationRequest(request.language, request.level, request.minutes, request.kind, request.topics, request.prompt, request.format),
            wordsPerMinute = store.wordsPerMinute(request.level)!!,
            translateInto = interfaceLanguages - request.language,
            questionLanguages = interfaceLanguages,
            topics = store.topics(),
            targetWords = knowledge.targetWords.take(GenerationContext.MAX_TARGET_WORDS),
            dictionaryLemmas = store.dictionaryLemmas(request.language, GenerationContext.MAX_DICTIONARY_LEMMAS),
            knownLemmas = knowledge.knownLemmas.take(GenerationContext.MAX_KNOWN_LEMMAS),
            knownEntries = knowledge.knownEntries,
            recentTitles = knowledge.recentTitles.take(GenerationContext.MAX_RECENT_TITLES),
            weakQuestionTypes = knowledge.weakQuestionTypes,
        )
    }

    suspend fun run(job: StoredJob) {
        try {
            val sink = sinks[job.target] ?: error("no sink for ${job.target}")
            val context = context(job.request, sink.knowledge(job))
            val slug = slugOf(job.request.prompt ?: "lectura", job.id)
            val outcome = generator.generate(context, slug, resolvers.load(job.request.language))
            val usage = mutableMapOf("inputTokens" to outcome.usage.input, "outputTokens" to outcome.usage.output, "illustrations" to 0L)
            if (outcome is Outcome.Failed) {
                store.fail(job.id, outcome.reason.name, outcome.findings.map { mapOf("code" to it.code, "ref" to it.ref, "detail" to it.detail) }, usage)
                return
            }
            outcome as Outcome.Ready
            var draft = outcome.draft.copy(slug = slugOf(outcome.draft.title, job.id))
            var illustrated: IllustratedBook? = null
            if (draft.format == ReadingFormat.PICTURE_BOOK) {
                store.status(job.id, JobStatus.ILLUSTRATING)
                illustrated = illustrator.illustrate(brief(draft, outcome.generated, context))
                draft = withIllustrations(draft, illustrated, job.actor)
                usage["illustrations"] = illustrated.pages.count { it.picture != null }.toLong()
            }
            val delivered = sink.deliver(job, draft, outcome.questions)
            store.ready(job.id, delivered.draftId, delivered.readingSlug, illustrated?.bible?.let(Jsonb::of), illustrated?.directedBy, usage)
        } catch (error: Exception) {
            log.error("encargo {}: {}", job.id, error.toString(), error)
            store.fail(job.id, "ERROR", listOf(mapOf("code" to "ERROR", "ref" to null, "detail" to (error.message ?: error.javaClass.simpleName))), emptyMap())
        }
    }

    // lo que se ilustra: las páginas con ilustración del borrador, con su escena y el texto que acompañan
    fun brief(
        draft: ReadingDraft,
        generated: GeneratedReading,
        context: GenerationContext,
    ): IllustrationBrief {
        val book = generated.book!!
        var next = 0
        val pages =
            draft.pages.mapIndexedNotNull { i, page ->
                val text =
                    draft.paragraphs
                        .subList(next, next + page.paragraphs)
                        .joinToString(" ") { p -> p.sentences.joinToString(" ") { it.text } }
                next += page.paragraphs
                val layout = PageRules.effective(draft, page) ?: return@mapIndexedNotNull null
                if (!layout.illustrated) return@mapIndexedNotNull null
                BriefPage(
                    index = i,
                    layout = layout,
                    span = page.span,
                    scene = page.illustration?.scene.orEmpty(),
                    characters =
                        book.pages
                            .getOrNull(i)
                            ?.characters
                            .orEmpty(),
                    text = text,
                    alt = page.illustration?.alt.orEmpty(),
                )
            }
        val art = Art(book.art.style, book.art.palette, book.art.setting, book.art.characters.map { CharacterSheet(it.name, it.look) })
        return IllustrationBrief(draft.title, draft.language, draft.level, art, pages, context.questionLanguages)
    }

    // cada ilustración dibujada, guardada como archivo del libro; la que no salió queda pendiente con su texto alternativo
    private suspend fun withIllustrations(
        draft: ReadingDraft,
        book: IllustratedBook,
        actor: String,
    ): ReadingDraft {
        val byPage = book.pages.associateBy { it.page }
        return draft.copy(
            pages =
                draft.pages.mapIndexed { i, page ->
                    val result = byPage[i] ?: return@mapIndexed page
                    val file = result.picture?.let { media.save(MediaKind.ILLUSTRATION, it.bytes, actor).file }
                    page.copy(illustration = page.illustration?.copy(file = file, alt = result.alt.ifEmpty { page.illustration.alt }))
                },
        )
    }

    companion object {
        // un slug legible a partir del título, con un sufijo del encargo para no chocar
        fun slugOf(
            title: String,
            id: UUID,
        ): String {
            val base =
                Normalizer
                    .normalize(title.replace("ß", "ss"), Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "")
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .take(80)
                    .trimEnd('-')
                    .ifEmpty { "lectura" }
            return "$base-${id.toString().take(6)}"
        }
    }
}

// la cola de encargos: un trabajador por lesen.generation.workers. Solo corre si hay quien escriba
@Component
class CommissionWorker(
    private val service: CommissionService,
    private val store: CommissionStore,
    private val properties: LesenProperties,
) {
    private val log = LoggerFactory.getLogger(CommissionWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.generation.worker) {
            log.info("encargos: este proceso no atiende la cola (lesen.generation.worker=false)")
            return
        }
        if (!service.available) {
            log.info("encargos desactivados: sin clave no se escriben lecturas a medida ni libros")
            return
        }
        repeat(properties.generation.workers.coerceIn(1, 8)) {
            scope.launch {
                while (isActive) {
                    try {
                        store.recover(properties.generation.staleMinutes)
                        val job = store.claim()
                        if (job == null) delay(properties.generation.pollMillis) else service.run(job)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log.warn("cola de encargos: {}", error.message)
                        delay(properties.generation.pollMillis)
                    }
                }
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()
}
