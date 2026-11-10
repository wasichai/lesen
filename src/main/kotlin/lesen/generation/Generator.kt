package lesen.generation

import lesen.assessment.Question
import lesen.common.LesenProperties
import lesen.reading.AssembledVersion
import lesen.reading.FormResolver
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.Tokenizers
import org.springframework.stereotype.Component
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException

// un intento: lo que salió de él y lo que encontró la comprobación
data class Attempt(
    val usage: TokenUsage,
    val findings: List<Finding>,
    val measures: Measures?,
)

sealed interface Outcome {
    val attempts: List<Attempt>
    val usage get() = attempts.fold(TokenUsage()) { sum, a -> sum + a.usage }

    // pasó todas las comprobaciones: se puede publicar, privada (fase 2)
    data class Ready(
        val draft: ReadingDraft,
        val questions: List<Question>,
        val assembled: AssembledVersion,
        val generated: GeneratedReading,
        override val attempts: List<Attempt>,
    ) : Outcome

    // no hay lectura: el motivo se le dice a la persona tal cual
    data class Failed(
        val reason: Reason,
        val findings: List<Finding>,
        override val attempts: List<Attempt>,
    ) : Outcome

    enum class Reason { REFUSED, UNREADABLE, CHECKS }
}

// el flujo de una lectura a medida (ADR-0013): el código reúne el contexto, pide la lectura, la comprueba y, si falla,
// la devuelve con sus fallos para que se corrija, hasta maxCorrections veces. Todo es determinista salvo la escritura
@Component
class Generator(
    private val writer: ReadingWriter,
    properties: LesenProperties,
) {
    private val maxCorrections = properties.generation.maxCorrections
    private val tokenizers = Tokenizers()

    val available get() = writer.available

    suspend fun generate(
        context: GenerationContext,
        slug: String,
        resolver: FormResolver,
        thresholds: GenerationCheck.Thresholds = GenerationCheck.Thresholds(),
    ): Outcome {
        val session = writer.start(context)
        val attempts = mutableListOf<Attempt>()
        var written = session.write()
        while (true) {
            val findings: List<Finding>
            when (written) {
                is Written.Refused -> {
                    attempts += Attempt(written.usage, emptyList(), null)
                    return Outcome.Failed(Outcome.Reason.REFUSED, emptyList(), attempts)
                }
                is Written.Unreadable -> {
                    findings = listOf(Finding("FORMAT", null, written.reason))
                    attempts += Attempt(written.usage, findings, null)
                    if (attempts.size > maxCorrections) return Outcome.Failed(Outcome.Reason.UNREADABLE, findings, attempts)
                }
                is Written.Reading -> {
                    val evaluated = evaluate(written.reading, context, slug, resolver, thresholds)
                    findings = evaluated.result.findings
                    attempts += Attempt(written.usage, findings, evaluated.result.measures)
                    if (findings.isEmpty()) {
                        return Outcome.Ready(evaluated.draft, evaluated.questions, evaluated.assembled!!, written.reading, attempts)
                    }
                    if (attempts.size > maxCorrections) return Outcome.Failed(Outcome.Reason.CHECKS, findings, attempts)
                }
            }
            written = session.correct(findings)
        }
    }

    data class Evaluated(
        val draft: ReadingDraft,
        val assembled: AssembledVersion?,
        val questions: List<Question>,
        val result: CheckResult,
    )

    // ensambla como al publicar y comprueba; pura salvo el resolvedor que se le pasa
    fun evaluate(
        generated: GeneratedReading,
        context: GenerationContext,
        slug: String,
        resolver: FormResolver,
        thresholds: GenerationCheck.Thresholds = GenerationCheck.Thresholds(),
    ): Evaluated {
        val draft = GeneratedDrafts.draft(generated, context, slug)
        var fieldProblems = emptyList<FieldViolation>()
        val assembled =
            try {
                ReadingAssembler.assemble(draft, tokenizers.forLanguage(draft.language), resolver)
            } catch (error: ValidationException) {
                fieldProblems = error.violations.ifEmpty { listOf(FieldViolation("reading", error.message ?: "invalid")) }
                null
            }
        val converted = assembled?.let { GeneratedDrafts.questions(generated.questions, it.content) }
        val questions = converted?.questions.orEmpty()
        val result = GenerationCheck.check(context, generated, assembled, fieldProblems, questions, converted?.problems.orEmpty(), thresholds)
        return Evaluated(draft, assembled, questions, result)
    }
}
