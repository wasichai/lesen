package lesen.assessment

import lesen.reading.ReadingContent
import lesen.reading.TokenKind

// un problema de una pregunta, con su id (o null si es del cuestionario entero)
data class QuestionProblem(
    val question: String?,
    val message: String,
)

// la validación de unas preguntas contra el texto que citan (ADR-0009, ADR-0012). Pura: el estudio la usa antes de
// publicar y nada se publica con preguntas que señalan oraciones o palabras que no existen
object QuestionCheck {
    const val MAX_QUESTIONS = 30
    private val ID = Regex("^[A-Za-z0-9_-]{1,16}$")

    fun problems(
        questions: List<Question>,
        content: ReadingContent,
    ): List<QuestionProblem> {
        val problems = mutableListOf<QuestionProblem>()
        if (questions.size > MAX_QUESTIONS) problems += QuestionProblem(null, "at most $MAX_QUESTIONS questions")
        questions
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { problems += QuestionProblem(it, "the id is repeated") }
        val sentences = content.sentences.associateBy { it.id }
        val words = content.words.associateBy { it.id }
        for (q in questions) {
            fun problem(message: String) {
                problems += QuestionProblem(q.id, message)
            }
            if (!ID.matches(q.id)) problem("the id must be 1–16 letters, digits, - or _")
            if (q.difficulty !in 1..3) problem("difficulty must be 1, 2 or 3")
            if (q.prompt.values.none { it.isNotBlank() }) problem("the question needs a prompt")
            if (q.refs.isEmpty()) problem("the question must cite at least one sentence")
            q.refs.filter { it !in sentences }.forEach { problem("$it is not a sentence of this text") }
            val spec =
                try {
                    q.parsed()
                } catch (_: Exception) {
                    problem("its details do not match a ${q.type} question")
                    continue
                }
            when (spec) {
                is ChoiceSpec -> {
                    items(spec.options, "options", 2, ::problem)
                    if (spec.correct.isEmpty()) problem("mark at least one correct option")
                    spec.correct.filter { id -> spec.options.none { it.id == id } }.forEach { problem("the correct option $it does not exist") }
                    if (q.type == QuestionType.VOCABULARY_IN_CONTEXT) {
                        if (spec.token == null || words[spec.token]?.kind != TokenKind.WORD) problem("choose the word the question is about")
                    }
                }
                is TrueFalseSpec -> {}
                is CompletionSpec -> {
                    val sentence = sentences[spec.sentence]
                    if (sentence == null) {
                        problem("${spec.sentence} is not a sentence of this text")
                    } else {
                        if (spec.blanks.isEmpty()) problem("choose at least one word to leave blank")
                        val own =
                            sentence.tokens
                                .filter { it.kind == TokenKind.WORD }
                                .map { it.id }
                                .toSet()
                        spec.blanks.filter { it !in own }.forEach { problem("$it is not a word of ${spec.sentence}") }
                    }
                }
                is MatchingSpec -> {
                    items(spec.left, "left", 2, ::problem)
                    items(spec.right, "right", 2, ::problem)
                    if (spec.pairs.keys != spec.left.map { it.id }.toSet()) problem("every item on the left needs its pair")
                    spec.pairs.values
                        .filter { id -> spec.right.none { it.id == id } }
                        .forEach { problem("the pair $it does not exist") }
                }
                is OrderingSpec -> {
                    items(spec.items, "items", 2, ::problem)
                    if (spec.order.sorted() != spec.items.map { it.id }.sorted()) problem("the order must use every item once")
                }
                is ShortAnswerSpec -> if (spec.model.values.none { it.isNotBlank() }) problem("write a model answer")
            }
        }
        return problems
    }

    private fun items(
        items: List<Item>,
        name: String,
        min: Int,
        problem: (String) -> Unit,
    ) {
        if (items.size < min) problem("$name needs at least $min items")
        if (items.map { it.id }.toSet().size != items.size) problem("$name has repeated ids")
        if (items.any { blank(it.text) }) problem("$name has an empty item")
    }

    private fun blank(text: Any): Boolean =
        when (text) {
            is String -> text.isBlank()
            is Map<*, *> -> text.values.none { it is String && it.isNotBlank() }
            else -> true
        }
}
