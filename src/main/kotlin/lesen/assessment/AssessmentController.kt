package lesen.assessment

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lesen.common.Access
import lesen.common.LesenProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/lesen")
class AssessmentController(
    private val access: Access,
    private val service: AssessmentService,
) {
    // las preguntas son contenido (lectura), el resumen del último intento es de quien pregunta (cuaderno)
    @GetMapping("/readings/{slug}/versions/{version}/assessment")
    suspend fun assessment(
        @PathVariable slug: String,
        @PathVariable version: Int,
        @RequestParam language: String?,
    ): AssessmentView {
        val reader = access.reader()
        return service.forReading(reader, access.notebookIfAllowed() != null, slug, version, language)
    }

    @PostMapping("/assessments/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun submit(
        @PathVariable id: UUID,
        @RequestBody request: SubmitAttempt,
    ): AttemptView {
        access.reader()
        return service.submit(access.writeNotebook(), id, request)
    }

    // con respuestas abiertas en corrección, la interfaz vuelve a pedir el intento hasta que no queda ninguna PENDING
    @GetMapping("/attempts/{id}")
    suspend fun attempt(
        @PathVariable id: UUID,
    ): AttemptView = service.attempt(access.notebook(), id)

    @PostMapping("/attempts/{id}/answers/{questionId}/self-assessment")
    suspend fun selfAssess(
        @PathVariable id: UUID,
        @PathVariable questionId: String,
        @RequestBody request: SelfAssessment,
    ): AttemptView = service.selfAssess(access.writeNotebook(), id, questionId, request)
}

// la cola de la corrección asistida: solo corre si hay asistente. Varios procesos pueden correrla a la vez (SKIP LOCKED)
@Component
class EvaluationWorker(
    private val service: AssessmentService,
    private val store: AssessmentStore,
    private val evaluator: AnswerEvaluator,
    private val properties: LesenProperties,
) {
    private val log = LoggerFactory.getLogger(EvaluationWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!evaluator.available) {
            log.info("corrección asistida desactivada: sin clave, las respuestas abiertas se autoevalúan")
            return
        }
        scope.launch {
            while (isActive) {
                try {
                    val jobs = store.claim(BATCH)
                    jobs.forEach { service.evaluate(it) }
                    if (jobs.isEmpty()) delay(properties.ai.pollMillis)
                } catch (error: Exception) {
                    log.warn("cola de corrección: {}", error.message)
                    delay(properties.ai.pollMillis)
                }
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        const val BATCH = 4
    }
}
