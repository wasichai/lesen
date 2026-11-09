package lesen.assessment

import lesen.common.Jsonb
import lesen.common.localized

// las preguntas de comprensión de una versión (ADR-0009, docs/content/demo-content.md §Preguntas). Se guardan tal como
// las escribe la autora; aquí se leen con su forma de cada tipo
enum class QuestionType {
    MULTIPLE_CHOICE,
    TRUE_FALSE,
    COMPLETION,
    MATCHING,
    ORDERING,
    SHORT_ANSWER,
    VOCABULARY_IN_CONTEXT,
    MAIN_IDEA,
    INFERENCE,
    DETAIL,
    ;

    // los tipos que se responden eligiendo opciones
    val choice get() = this in setOf(MULTIPLE_CHOICE, VOCABULARY_IN_CONTEXT, MAIN_IDEA, INFERENCE, DETAIL)
}

data class AssessmentSeed(
    val reading: String,
    val questions: List<Question>,
)

data class Question(
    val id: String,
    val type: QuestionType,
    val difficulty: Int,
    val prompt: Map<String, String>,
    val explanation: Map<String, String>,
    val refs: List<String>,
    val spec: Map<String, Any?>,
) {
    fun parsed(): Spec =
        when {
            type.choice -> Jsonb.MAPPER.convertValue(spec, ChoiceSpec::class.java)
            type == QuestionType.TRUE_FALSE -> Jsonb.MAPPER.convertValue(spec, TrueFalseSpec::class.java)
            type == QuestionType.COMPLETION -> Jsonb.MAPPER.convertValue(spec, CompletionSpec::class.java)
            type == QuestionType.MATCHING -> Jsonb.MAPPER.convertValue(spec, MatchingSpec::class.java)
            type == QuestionType.ORDERING -> Jsonb.MAPPER.convertValue(spec, OrderingSpec::class.java)
            else -> Jsonb.MAPPER.convertValue(spec, ShortAnswerSpec::class.java)
        }
}

// el texto de una opción o elemento: una cadena en el idioma de la lectura, o {es, en} en el de la persona
data class Item(
    val id: String,
    val text: Any,
)

sealed interface Spec

data class ChoiceSpec(
    val options: List<Item>,
    val correct: List<String>,
    // VOCABULARY_IN_CONTEXT: la palabra por la que se pregunta
    val token: String? = null,
) : Spec

data class TrueFalseSpec(
    val correct: Boolean,
) : Spec

data class CompletionSpec(
    val sentence: String,
    val blanks: List<String>,
    val accepted: Map<String, List<String>> = emptyMap(),
    val bank: List<String> = emptyList(),
) : Spec

data class MatchingSpec(
    val left: List<Item>,
    val right: List<Item>,
    val pairs: Map<String, String>,
) : Spec

data class OrderingSpec(
    val items: List<Item>,
    val order: List<String>,
) : Spec

data class ShortAnswerSpec(
    val model: Map<String, String>,
    val rubric: Map<String, String>,
) : Spec

// un texto ya resuelto para quien pregunta, con el idioma en que está (para el atributo lang de la interfaz)
data class LocalText(
    val text: String,
    val lang: String,
)

@Suppress("UNCHECKED_CAST")
fun Any.resolve(
    interfaceLanguage: String,
    readingLanguage: String,
): LocalText =
    when (this) {
        is String -> LocalText(this, readingLanguage)
        is Map<*, *> -> {
            val map = this as Map<String, String>
            val language = if (map.containsKey(interfaceLanguage)) interfaceLanguage else map.keys.firstOrNull() ?: interfaceLanguage
            LocalText(map.localized(interfaceLanguage), language)
        }
        else -> LocalText(toString(), readingLanguage)
    }

// lo que responde la persona, con el campo de su tipo
data class AnswerInput(
    val questionId: String,
    val options: List<String>? = null,
    val value: Boolean? = null,
    val blanks: Map<String, String>? = null,
    val pairs: Map<String, String>? = null,
    val order: List<String>? = null,
    val text: String? = null,
)

enum class AnswerResult { CORRECT, PARTIAL, INCORRECT, PENDING, UNGRADED }

enum class Evaluator { RULE, AI, SELF }
