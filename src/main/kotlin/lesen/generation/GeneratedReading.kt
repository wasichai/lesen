package lesen.generation

import lesen.assessment.Question
import lesen.assessment.QuestionType
import lesen.reading.DraftParagraph
import lesen.reading.DraftSentence
import lesen.reading.ReadingContent
import lesen.reading.ReadingDraft
import lesen.reading.TokenKind
import lesen.reading.Tokenizer

// lo que devuelve el modelo, con la forma del esquema de GenerationPrompt. Las oraciones las fija el modelo; los ids de
// párrafo y oración (p1s2) salen de su posición y los de palabra los pone nuestro tokenizador al ensamblar
data class GeneratedReading(
    val title: String,
    val subtitle: String?,
    val summary: String,
    val topics: List<String>,
    // nombres propios del texto: no cuentan como palabras sin entrada
    val names: List<String>,
    val paragraphs: List<GeneratedParagraph>,
    val questions: List<GeneratedQuestion>,
)

data class GeneratedParagraph(
    val sentences: List<GeneratedSentence>,
)

data class GeneratedSentence(
    val text: String,
    val translations: Map<String, String>,
)

// una pregunta en la forma plana del esquema: cada tipo usa sus campos y deja los demás vacíos. La palabra (word,
// blanks) se escribe tal como aparece en la primera oración citada, y el código la convierte en su id
data class GeneratedQuestion(
    val id: String,
    val type: QuestionType,
    val difficulty: Int,
    val prompt: Map<String, String>,
    val explanation: Map<String, String>,
    val refs: List<String>,
    val options: List<GeneratedOption>,
    val correct: List<String>,
    val answer: Boolean?,
    val word: String?,
    val blanks: List<String>,
)

data class GeneratedOption(
    val id: String,
    val text: Map<String, String>,
)

// de la salida del modelo a lo que Lesen publica
object GeneratedDrafts {
    // los tipos que el modelo puede escribir: los que se corrigen por reglas y se citan con oraciones y palabras
    val TYPES =
        listOf(
            QuestionType.MAIN_IDEA,
            QuestionType.DETAIL,
            QuestionType.INFERENCE,
            QuestionType.MULTIPLE_CHOICE,
            QuestionType.VOCABULARY_IN_CONTEXT,
            QuestionType.TRUE_FALSE,
            QuestionType.COMPLETION,
        )

    // la traducción de un párrafo es la de sus oraciones, unidas: el modelo no la escribe dos veces
    fun draft(
        generated: GeneratedReading,
        context: GenerationContext,
        slug: String,
    ): ReadingDraft =
        ReadingDraft(
            slug = slug,
            language = context.request.language,
            level = context.request.level,
            topics = generated.topics.filter { it in context.topics }.distinct(),
            title = generated.title.trim(),
            subtitle = generated.subtitle?.trim()?.takeIf { it.isNotEmpty() },
            summary = generated.summary.trim(),
            paragraphs =
                generated.paragraphs.map { paragraph ->
                    val sentences =
                        paragraph.sentences.map { s ->
                            DraftSentence(s.text.trim(), s.translations.filterKeys { it in context.translateInto }.mapValues { it.value.trim() })
                        }
                    val translations =
                        context.translateInto
                            .associateWith { language -> sentences.mapNotNull { it.translations[language] }.joinToString(" ") }
                            .filterValues { it.isNotBlank() }
                    DraftParagraph(sentences, translations)
                },
        )

    data class Converted(
        val questions: List<Question>,
        // las palabras citadas que no están en su oración
        val problems: List<Pair<String, String>>,
    )

    // las preguntas, con las palabras convertidas en ids de token del texto ensamblado
    fun questions(
        generated: List<GeneratedQuestion>,
        content: ReadingContent,
    ): Converted {
        val problems = mutableListOf<Pair<String, String>>()
        val questions =
            generated.map { q ->
                val sentence = q.refs.firstOrNull()?.let(content::sentence)

                fun tokens(word: String): List<String> {
                    val form = Tokenizer.normalize(word.trim())
                    val ids =
                        sentence
                            ?.tokens
                            ?.filter { it.kind == TokenKind.WORD && Tokenizer.normalize(it.text) == form }
                            ?.map { it.id }
                            .orEmpty()
                    if (ids.isEmpty()) problems += q.id to "«$word» is not a word of ${q.refs.firstOrNull()}"
                    return ids
                }
                val options = q.options.map { mapOf("id" to it.id, "text" to it.text) }
                val spec: Map<String, Any?> =
                    when (q.type) {
                        QuestionType.TRUE_FALSE -> mapOf("correct" to (q.answer ?: false))
                        QuestionType.COMPLETION ->
                            mapOf(
                                "sentence" to q.refs.firstOrNull(),
                                // cada palabra pedida, en su primera aparición todavía libre
                                "blanks" to
                                    q.blanks
                                        .fold(listOf<String>()) { taken, word -> taken + listOfNotNull(tokens(word).firstOrNull { it !in taken }) },
                            )
                        QuestionType.VOCABULARY_IN_CONTEXT ->
                            mapOf("options" to options, "correct" to q.correct, "token" to q.word?.let { tokens(it).firstOrNull() })
                        else -> mapOf("options" to options, "correct" to q.correct)
                    }
                Question(q.id, q.type, q.difficulty, q.prompt, q.explanation, q.refs, spec)
            }
        return Converted(questions, problems)
    }
}
