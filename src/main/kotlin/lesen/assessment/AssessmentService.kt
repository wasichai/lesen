package lesen.assessment

import lesen.common.ActivityType
import lesen.common.Learner
import lesen.common.LearningActivity
import lesen.common.LearningActivityListener
import lesen.common.LesenProperties
import lesen.common.emit
import lesen.common.localized
import lesen.common.requireLength
import lesen.learner.Profiles
import lesen.reading.ReadingContent
import lesen.reading.ReadingVersions
import lesen.reading.TokenKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class OptionView(
    val id: String,
    val text: String,
    val lang: String,
)

// un trozo de la oración de un ejercicio de completar: texto, o el hueco de una palabra (su id de token)
data class Part(
    val text: String? = null,
    val blank: String? = null,
)

data class CompletionView(
    val sentence: String,
    val parts: List<Part>,
    val bank: List<String>,
)

data class WordRef(
    val id: String,
    val text: String,
    val sentence: String,
)

// una pregunta tal como la ve quien responde: sin la respuesta correcta
data class QuestionView(
    val id: String,
    val type: QuestionType,
    val difficulty: Int,
    val prompt: String,
    val refs: List<String>,
    val multiple: Boolean? = null,
    val options: List<OptionView>? = null,
    val word: WordRef? = null,
    val completion: CompletionView? = null,
    val left: List<OptionView>? = null,
    val right: List<OptionView>? = null,
    val items: List<OptionView>? = null,
)

data class AttemptSummary(
    val id: UUID,
    val submittedAt: Instant,
    val total: Int,
    val correct: Int,
    val partial: Int,
    val incorrect: Int,
    val pending: Int,
    val ungraded: Int,
)

data class AssessmentView(
    val id: UUID,
    val slug: String,
    val version: Int,
    val language: String,
    val questions: List<QuestionView>,
    val lastAttempt: AttemptSummary?,
)

// la respuesta correcta, ya resuelta, para enseñarla después de responder
data class Solution(
    val options: List<String>? = null,
    val value: Boolean? = null,
    val blanks: Map<String, String>? = null,
    val pairs: Map<String, String>? = null,
    val order: List<String>? = null,
    val text: String? = null,
)

data class AnswerView(
    val questionId: String,
    val type: QuestionType,
    val result: AnswerResult,
    val evaluator: Evaluator?,
    val response: AnswerInput,
    val solution: Solution,
    val explanation: String,
    val refs: List<String>,
    val justification: String?,
    val citations: List<String>,
)

data class AttemptView(
    val id: UUID,
    val assessmentId: UUID,
    val slug: String,
    val version: Int,
    val submittedAt: Instant,
    val summary: AttemptSummary,
    val questions: List<QuestionView>,
    val answers: List<AnswerView>,
)

data class SubmitAttempt(
    val answers: List<AnswerInput> = emptyList(),
)

data class SelfAssessment(
    val result: AnswerResult,
)

@Service
class AssessmentService(
    private val store: AssessmentStore,
    private val versions: ReadingVersions,
    private val profiles: Profiles,
    private val evaluator: AnswerEvaluator,
    private val properties: LesenProperties,
    private val tx: TransactionalOperator,
    private val activity: List<LearningActivityListener>,
) {
    private val log = LoggerFactory.getLogger(AssessmentService::class.java)

    // las preguntas de la versión pedida (o la vigente), sin respuestas, y el resumen del último intento de quien pregunta
    suspend fun forReading(
        me: Learner,
        personal: Boolean,
        slug: String,
        number: Int?,
        language: String?,
    ): AssessmentView {
        val ref = versions.find(slug, number) ?: throw NotFoundException("Reading version not found")
        val assessment = store.forVersion(ref.versionId) ?: throw NotFoundException("No questions for this reading")
        val into = language(me, language)
        val content = versions.content(ref.versionId)
        val last = if (personal) store.latestAttempt(me.organizationId, me.userId, assessment.id) else null
        return AssessmentView(
            assessment.id,
            ref.slug,
            ref.number,
            ref.language,
            assessment.questions.map { view(it, content, into, ref.language) },
            last?.let { summary(it, store.answers(it.id), assessment.questions.size) },
        )
    }

    // corrige lo cerrado al momento y deja lo abierto al asistente (cola) o, sin él, para autoevaluar
    suspend fun submit(
        me: Learner,
        assessmentId: UUID,
        request: SubmitAttempt,
    ): AttemptView {
        val assessment = store.byId(assessmentId) ?: throw NotFoundException("Assessment not found")
        val byId = assessment.questions.associateBy { it.id }
        validate(request, byId)
        val content = versions.content(assessment.versionId)
        val words = content.words.associate { it.id to it.text }
        val given = request.answers.associateBy { it.questionId }
        val into = language(me, null)
        val assisted =
            evaluator.available &&
                store.recentJobs(me.organizationId, me.userId) + assessment.questions.count { it.type == QuestionType.SHORT_ANSWER } <=
                properties.ai.hourlyLimit
        val attemptId =
            tx.executeAndAwait {
                val id = store.insertAttempt(me.organizationId, me.userId, assessment.id, into)
                for (question in assessment.questions) {
                    val answer = given[question.id] ?: AnswerInput(question.id)
                    val graded = Grader.grade(question, given[question.id], words)
                    val result = graded ?: if (assisted) AnswerResult.PENDING else AnswerResult.UNGRADED
                    store.insertAnswer(
                        id,
                        question.type,
                        StoredAnswer(
                            question.id,
                            answer,
                            result,
                            if (graded !=
                                null
                            ) {
                                Evaluator.RULE
                            } else {
                                null
                            },
                            null,
                            emptyList(),
                        ),
                    )
                    if (result == AnswerResult.PENDING) store.enqueue(me.organizationId, me.userId, id, question.id)
                }
                id
            }
        val view = attempt(me, attemptId)
        val reading = versions.find(view.slug, view.version)!!
        activity.emit(
            LearningActivity(
                me.organizationId,
                me.userId,
                ActivityType.ASSESSMENT_SUBMITTED,
                view.submittedAt,
                reading.language,
                "attempt:$attemptId",
                reading.readingId,
            ),
        )
        return view
    }

    suspend fun attempt(
        me: Learner,
        id: UUID,
    ): AttemptView {
        val attempt = store.attempt(me.organizationId, me.userId, id) ?: throw NotFoundException("Attempt not found")
        val assessment = store.byId(attempt.assessmentId)!!
        val content = versions.content(attempt.versionId)
        val readingLanguage = versions.find(attempt.slug, attempt.version)!!.language
        val answers = store.answers(id).associateBy { it.questionId }
        return AttemptView(
            attempt.id,
            attempt.assessmentId,
            attempt.slug,
            attempt.version,
            attempt.submittedAt,
            summary(attempt, answers.values.toList(), assessment.questions.size),
            assessment.questions.map { view(it, content, attempt.language, readingLanguage) },
            assessment.questions.mapNotNull { q -> answers[q.id]?.let { answerView(q, it, content, attempt.language) } },
        )
    }

    // sin asistente, la persona compara su respuesta con la modelo y dice cómo le fue; solo sobre lo que nadie corrigió
    suspend fun selfAssess(
        me: Learner,
        attemptId: UUID,
        questionId: String,
        request: SelfAssessment,
    ): AttemptView {
        if (request.result !in setOf(AnswerResult.CORRECT, AnswerResult.PARTIAL, AnswerResult.INCORRECT)) {
            throw ValidationException("Invalid result", "result", "must be CORRECT, PARTIAL or INCORRECT")
        }
        store.attempt(me.organizationId, me.userId, attemptId) ?: throw NotFoundException("Attempt not found")
        if (!store.grade(attemptId, questionId, request.result, Evaluator.SELF, null, emptyList(), onlyIf = AnswerResult.UNGRADED)) {
            throw ConflictException("This answer is not waiting for a self-assessment")
        }
        return attempt(me, attemptId)
    }

    // un trabajo de la cola: corrige con el asistente; si falla, reintenta con espera y al tercer fallo deja la
    // respuesta para autoevaluar. Nunca inventa un resultado
    suspend fun evaluate(job: EvaluationJob) {
        try {
            val attempt = store.attemptForJob(job.attemptId) ?: return store.finish(job.id, "DONE", "attempt gone", null)
            val question = store.byId(attempt.assessmentId)!!.questions.first { it.id == job.questionId }
            val answer = store.answers(job.attemptId).first { it.questionId == job.questionId }
            val spec = question.parsed() as ShortAnswerSpec
            val content = versions.content(attempt.versionId)
            val language = attempt.language
            val request =
                EvaluationRequest(
                    prompt = question.prompt.localized(language),
                    rubric = spec.rubric.localized(language),
                    modelAnswer = spec.model.localized(language),
                    references = question.refs.mapNotNull { id -> content.sentence(id)?.let { EvaluationRequest.Reference(id, it.text) } },
                    answer =
                        answer.response.text
                            .orEmpty()
                            .take(MAX_TEXT),
                    readingLanguage = versions.find(attempt.slug, attempt.version)!!.language,
                    language = language,
                )
            val evaluation = AnswerEvaluator.validate(request, evaluator.evaluate(request))
            store.grade(job.attemptId, job.questionId, evaluation.result, Evaluator.AI, evaluation.justification, evaluation.citations, AnswerResult.PENDING)
            store.finish(job.id, "DONE", null, null)
        } catch (error: Exception) {
            log.warn("corrección asistida {} (intento {}): {}", job.id, job.tries, error.message)
            if (job.tries < MAX_TRIES) {
                store.finish(job.id, "QUEUED", error.message, RETRY_SECONDS shl (job.tries - 1))
            } else {
                store.grade(job.attemptId, job.questionId, AnswerResult.UNGRADED, null, null, emptyList(), AnswerResult.PENDING)
                store.finish(job.id, "FAILED", error.message, null)
            }
        }
    }

    // las preguntas guardadas de una versión (para volver a editarlas en el estudio) y si alguien ya las respondió:
    // entonces ya no se pueden cambiar en esa versión (ADR-0009)
    suspend fun questionsOf(versionId: UUID): List<Question>? = store.forVersion(versionId)?.questions

    suspend fun answered(versionId: UUID): Boolean = store.answered(versionId)

    // lo corregido de quien pregunta en un idioma, para el panel de progreso
    suspend fun comprehension(
        organizationId: UUID,
        userId: UUID,
        language: String,
    ): List<ComprehensionCount> = store.comprehension(organizationId, userId, language)

    // carga las preguntas del contenido de demostración contra la versión dada; idempotente por la huella del origen
    suspend fun load(
        versionId: UUID,
        questions: List<Question>,
    ): Boolean {
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    lesen.common.Jsonb
                        .of(questions)
                        .toByteArray(),
                ).joinToString("") { "%02x".format(it) }
        return store.save(versionId, questions, fingerprint)
    }

    private fun validate(
        request: SubmitAttempt,
        questions: Map<String, Question>,
    ) {
        val ids = request.answers.map { it.questionId }
        if (ids.size != ids.toSet().size) throw ValidationException("Duplicate answer", "answers", "one answer per question")
        for (answer in request.answers) {
            questions[answer.questionId]
                ?: throw ValidationException("Unknown question", "questionId", "${answer.questionId.take(16)} is not in this assessment")
            requireLength(answer.text, "text", MAX_TEXT)
            val sizes = listOf(answer.options?.size, answer.blanks?.size, answer.pairs?.size, answer.order?.size)
            if (sizes.any { (it ?: 0) > 10 }) throw ValidationException("Too many items", "answers", "at most 10 items per answer")
            answer.blanks?.values?.forEach { requireLength(it, "blanks", 64) }
        }
    }

    private suspend fun language(
        me: Learner,
        language: String?,
    ): String {
        if (language != null && language !in setOf("es", "en")) throw ValidationException("Invalid language", "language", "must be an interface language")
        return language ?: profiles.find(me.organizationId, me.userId)?.interfaceLanguage ?: "en"
    }

    private fun view(
        question: Question,
        content: ReadingContent,
        into: String,
        readingLanguage: String,
    ): QuestionView {
        fun options(items: List<Item>) = items.map { item -> item.text.resolve(into, readingLanguage).let { OptionView(item.id, it.text, it.lang) } }
        val base = QuestionView(question.id, question.type, question.difficulty, question.prompt.localized(into), question.refs)
        return when (val spec = question.parsed()) {
            is ChoiceSpec -> {
                val word =
                    spec.token?.let { id -> content.token(id)?.let { (sentence, token) -> WordRef(token.id, token.text, sentence.id) } }
                base.copy(multiple = spec.correct.size > 1, options = options(spec.options), word = word)
            }
            is TrueFalseSpec -> base
            is CompletionSpec -> {
                val sentence = content.sentence(spec.sentence)!!
                val parts = mutableListOf<Part>()
                val text = StringBuilder()
                for (token in sentence.tokens) {
                    if (token.kind == TokenKind.WORD && token.id in spec.blanks) {
                        if (text.isNotEmpty()) parts += Part(text = text.toString())
                        text.clear()
                        parts += Part(blank = token.id)
                        if (token.space) text.append(' ')
                    } else {
                        text.append(token.text)
                        if (token.space) text.append(' ')
                    }
                }
                if (text.isNotBlank()) parts += Part(text = text.toString())
                base.copy(completion = CompletionView(sentence.id, parts, spec.bank))
            }
            // la columna derecha en un orden estable que no delata las parejas
            is MatchingSpec -> base.copy(left = options(spec.left), right = options(spec.right.sortedBy { stableOrder(question.id, it.id) }))
            is OrderingSpec -> base.copy(items = options(spec.items))
            is ShortAnswerSpec -> base
        }
    }

    private fun answerView(
        question: Question,
        answer: StoredAnswer,
        content: ReadingContent,
        into: String,
    ): AnswerView {
        val solution =
            when (val spec = question.parsed()) {
                is ChoiceSpec -> Solution(options = spec.correct)
                is TrueFalseSpec -> Solution(value = spec.correct)
                is CompletionSpec ->
                    Solution(
                        blanks =
                            spec.blanks.associateWith {
                                content
                                    .token(it)
                                    ?.second
                                    ?.text
                                    .orEmpty()
                            },
                    )
                is MatchingSpec -> Solution(pairs = spec.pairs)
                is OrderingSpec -> Solution(order = spec.order)
                is ShortAnswerSpec -> Solution(text = spec.model.localized(into))
            }
        return AnswerView(
            question.id,
            question.type,
            answer.result,
            answer.evaluator,
            answer.response,
            solution,
            question.explanation.localized(into),
            question.refs,
            answer.justification,
            answer.citations,
        )
    }

    private fun summary(
        attempt: StoredAttempt,
        answers: List<StoredAnswer>,
        total: Int,
    ): AttemptSummary {
        val counts = answers.groupingBy { it.result }.eachCount()
        return AttemptSummary(
            attempt.id,
            attempt.submittedAt,
            total,
            counts[AnswerResult.CORRECT] ?: 0,
            counts[AnswerResult.PARTIAL] ?: 0,
            counts[AnswerResult.INCORRECT] ?: 0,
            counts[AnswerResult.PENDING] ?: 0,
            counts[AnswerResult.UNGRADED] ?: 0,
        )
    }

    private fun stableOrder(
        questionId: String,
        itemId: String,
    ) = MessageDigest.getInstance("SHA-256").digest("$questionId:$itemId".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_TEXT = 2000
        const val MAX_TRIES = 3
        const val RETRY_SECONDS = 15L
    }
}
