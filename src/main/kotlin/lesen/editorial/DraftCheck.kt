package lesen.editorial

import lesen.assessment.Question
import lesen.assessment.QuestionCheck
import lesen.audio.Timings
import lesen.reading.AssembledVersion
import wasichai.core.common.FieldViolation

// un hallazgo de la comprobación: un código que la interfaz explica con sus palabras, a qué se refiere (campo, oración,
// pregunta, palabra) y el detalle técnico si lo hay
data class Issue(
    val code: String,
    val ref: String? = null,
    val detail: String? = null,
)

data class CheckReport(
    // sin errores se puede enviar a revisión y publicar; los avisos no lo impiden
    val ready: Boolean,
    val errors: List<Issue>,
    val warnings: List<Issue>,
    val words: Int,
    val sentences: Int,
    val unresolved: Int,
    val missingTranslations: Int,
    val questions: Int,
    val alignedWords: Int?,
)

// lo que hay que saber para comprobar un borrador; lo reúne StudioService
data class CheckInput(
    val language: String,
    // los idiomas de interfaz en que debe traducirse (todos menos el de la lectura)
    val translateInto: List<String>,
    val fieldProblems: List<FieldViolation>,
    val assembled: AssembledVersion?,
    val questions: List<Question>,
    val audio: DraftAudio?,
    // la huella de la versión vigente, si la lectura ya existe
    val currentHash: String?,
    // las preguntas de la versión vigente y si ya las respondió alguien (entonces no cambian sin texto nuevo)
    val currentQuestions: List<Question>?,
    val currentAnswered: Boolean,
)

// la comprobación antes de publicar (ADR-0012): lo que impide publicar (errores) y lo que conviene mirar (avisos). Pura
object DraftCheck {
    fun report(input: CheckInput): CheckReport {
        val errors = input.fieldProblems.map { Issue("FIELD", it.field, it.message) }.toMutableList()
        val warnings = mutableListOf<Issue>()
        val assembled = input.assembled
        if (assembled == null) {
            return CheckReport(false, errors, warnings, 0, 0, 0, 0, input.questions.size, null)
        }
        val content = assembled.content
        assembled.unresolved.forEach { warnings += Issue("UNRESOLVED", it) }
        // las traducciones de cada oración y de cada párrafo, en cada idioma de interfaz
        var missing = 0
        val translated =
            assembled.translations
                .filter { it.text.isNotBlank() }
                .map { Triple(it.unit, it.ref, it.language) }
                .toSet()
        for (language in input.translateInto) {
            for (paragraph in content.paragraphs) {
                if (Triple("PARAGRAPH", paragraph.id, language) !in translated) {
                    missing++
                    warnings += Issue("TRANSLATION_MISSING", paragraph.id, language)
                }
                for (sentence in paragraph.sentences) {
                    if (Triple("SENTENCE", sentence.id, language) !in translated) {
                        missing++
                        warnings += Issue("TRANSLATION_MISSING", sentence.id, language)
                    }
                }
            }
        }
        // las preguntas: lo que citan existe en este texto, y sus textos en cada idioma de interfaz
        QuestionCheck.problems(input.questions, content).forEach { errors += Issue("QUESTION", it.question, it.message) }
        if (input.questions.isEmpty()) warnings += Issue("NO_QUESTIONS")
        for (q in input.questions) {
            for (language in input.translateInto) {
                if (q.prompt[language].isNullOrBlank()) warnings += Issue("PROMPT_MISSING", q.id, language)
                if (q.explanation[language].isNullOrBlank()) warnings += Issue("EXPLANATION_MISSING", q.id, language)
            }
        }
        // el audio: sus tiempos, contra los ids de este texto (si el texto cambió después de subirlo, ya no casan)
        var aligned: Int? = null
        val audio = input.audio
        if (audio == null) {
            warnings += Issue("NO_AUDIO")
        } else {
            val check = Timings.check(content, audio.timings, audio.durationMs)
            check.errors.take(20).forEach { errors += Issue("AUDIO", null, it) }
            aligned = content.words.size - check.unaligned.size
            if (check.valid && check.unaligned.isNotEmpty()) warnings += Issue("AUDIO_UNALIGNED", null, check.unaligned.size.toString())
            if (audio.multiWordCues > 0) warnings += Issue("AUDIO_MULTIWORD", null, audio.multiWordCues.toString())
        }
        // el mismo texto que la versión vigente: no habrá versión nueva, y sus preguntas solo cambian si nadie las respondió
        if (input.currentHash != null && input.currentHash == assembled.hash) {
            warnings += Issue("UNCHANGED")
            if (input.currentAnswered && input.currentQuestions != null && input.currentQuestions != input.questions) {
                errors += Issue("QUESTIONS_LOCKED")
            }
        }
        return CheckReport(
            ready = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            words = content.words.size,
            sentences = content.sentences.size,
            unresolved = assembled.unresolved.size,
            missingTranslations = missing,
            questions = input.questions.size,
            alignedWords = aligned,
        )
    }
}
