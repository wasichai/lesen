package lesen.assessment

import java.text.Normalizer

// la corrección determinista de todo lo cerrado (ADR-0009): misma respuesta, mismo resultado, sin modelo. Las
// respuestas abiertas (SHORT_ANSWER) no se corrigen aquí: devuelve null y las corrige el asistente o la propia persona
object Grader {
    // words: el texto de cada palabra de la versión que puede ser hueco (id de token → texto)
    fun grade(
        question: Question,
        answer: AnswerInput?,
        words: Map<String, String>,
    ): AnswerResult? {
        val spec = question.parsed()
        if (spec is ShortAnswerSpec) return if (answer?.text.isNullOrBlank()) AnswerResult.INCORRECT else null
        if (answer == null) return AnswerResult.INCORRECT
        return when (spec) {
            is ChoiceSpec -> choice(spec, answer.options.orEmpty().toSet())
            is TrueFalseSpec -> if (answer.value == spec.correct) AnswerResult.CORRECT else AnswerResult.INCORRECT
            is CompletionSpec -> {
                val given = answer.blanks.orEmpty()
                val right =
                    spec.blanks.count { blank ->
                        val accepted = (listOfNotNull(words[blank]) + spec.accepted[blank].orEmpty()).map(::normalize).toSet()
                        given[blank]?.let(::normalize) in accepted
                    }
                share(right, spec.blanks.size, partialFrom = 1)
            }
            is MatchingSpec -> {
                val given = answer.pairs.orEmpty()
                share(spec.pairs.count { (left, right) -> given[left] == right }, spec.pairs.size, partialFrom = (spec.pairs.size + 1) / 2)
            }
            is OrderingSpec -> {
                val given = answer.order.orEmpty()
                if (given.sorted() != spec.order.sorted()) return AnswerResult.INCORRECT
                share(spec.order.indices.count { given[it] == spec.order[it] }, spec.order.size, partialFrom = (spec.order.size + 1) / 2)
            }
            is ShortAnswerSpec -> null
        }
    }

    // una sola correcta: acierto o fallo. Varias: todas y ninguna de más es acierto; solo correctas pero no todas, parcial
    private fun choice(
        spec: ChoiceSpec,
        chosen: Set<String>,
    ): AnswerResult {
        val correct = spec.correct.toSet()
        return when {
            chosen == correct -> AnswerResult.CORRECT
            correct.size > 1 && chosen.isNotEmpty() && correct.containsAll(chosen) -> AnswerResult.PARTIAL
            else -> AnswerResult.INCORRECT
        }
    }

    private fun share(
        right: Int,
        total: Int,
        partialFrom: Int,
    ) = when {
        right == total -> AnswerResult.CORRECT
        right >= partialFrom && right > 0 -> AnswerResult.PARTIAL
        else -> AnswerResult.INCORRECT
    }

    // lo que no cuenta al comparar un hueco: mayúsculas, espacios de más, signos alrededor y la forma del apóstrofo
    fun normalize(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFC)
            .replace('’', '\'')
            .lowercase()
            .trim()
            .trim { !it.isLetterOrDigit() }
            .replace(Regex("\\s+"), " ")
}
