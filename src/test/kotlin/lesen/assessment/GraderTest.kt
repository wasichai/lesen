package lesen.assessment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GraderTest {
    private fun question(
        type: QuestionType,
        spec: Map<String, Any?>,
    ) = Question("q1", type, 1, mapOf("es" to "¿?", "en" to "?"), mapOf("es" to "—", "en" to "—"), listOf("p1s1"), spec)

    private val options = listOf(mapOf("id" to "a", "text" to "A"), mapOf("id" to "b", "text" to "B"), mapOf("id" to "c", "text" to "C"))

    @Test
    fun `choice with one right option is right or wrong`() {
        val q = question(QuestionType.DETAIL, mapOf("options" to options, "correct" to listOf("b")))
        assertThat(Grader.grade(q, AnswerInput("q1", options = listOf("b")), emptyMap())).isEqualTo(AnswerResult.CORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", options = listOf("a")), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", options = listOf("a", "b")), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
        assertThat(Grader.grade(q, null, emptyMap())).isEqualTo(AnswerResult.INCORRECT)
    }

    @Test
    fun `choice with several right options gives partial credit only without wrong ones`() {
        val q = question(QuestionType.MULTIPLE_CHOICE, mapOf("options" to options, "correct" to listOf("a", "b")))
        assertThat(Grader.grade(q, AnswerInput("q1", options = listOf("b", "a")), emptyMap())).isEqualTo(AnswerResult.CORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", options = listOf("a")), emptyMap())).isEqualTo(AnswerResult.PARTIAL)
        assertThat(Grader.grade(q, AnswerInput("q1", options = listOf("a", "c")), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", options = emptyList()), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
    }

    @Test
    fun `true or false`() {
        val q = question(QuestionType.TRUE_FALSE, mapOf("correct" to false))
        assertThat(Grader.grade(q, AnswerInput("q1", value = false), emptyMap())).isEqualTo(AnswerResult.CORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", value = true), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1"), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
    }

    @Test
    fun `completion accepts the word of the text and its variants, ignoring case, spaces and punctuation`() {
        val q =
            question(
                QuestionType.COMPLETION,
                mapOf("sentence" to "p1s1", "blanks" to listOf("p1s1t2", "p1s1t5"), "accepted" to mapOf("p1s1t5" to listOf("colour"))),
            )
        val words = mapOf("p1s1t2" to "Schlüssel", "p1s1t5" to "color")
        assertThat(Grader.grade(q, AnswerInput("q1", blanks = mapOf("p1s1t2" to "  schlüssel. ", "p1s1t5" to "Colour")), words)).isEqualTo(AnswerResult.CORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", blanks = mapOf("p1s1t2" to "Schlussel", "p1s1t5" to "color")), words)).isEqualTo(AnswerResult.PARTIAL)
        assertThat(Grader.grade(q, AnswerInput("q1", blanks = mapOf("p1s1t2" to "Tür")), words)).isEqualTo(AnswerResult.INCORRECT)
        // la forma decompuesta de la ü es la misma letra
        assertThat(Grader.grade(q, AnswerInput("q1", blanks = mapOf("p1s1t2" to "Schlüssel", "p1s1t5" to "color")), words)).isEqualTo(AnswerResult.CORRECT)
    }

    @Test
    fun `matching counts pairs, half or more is partial`() {
        val left =
            listOf(
                mapOf("id" to "1", "text" to "Hund"),
                mapOf("id" to "2", "text" to "Katze"),
                mapOf("id" to "3", "text" to "Maus"),
                mapOf(
                    "id" to "4",
                    "text" to "Fuchs",
                ),
            )
        val right = listOf("x", "y", "z", "w", "v").map { mapOf("id" to it, "text" to mapOf("es" to it, "en" to it)) }
        val q = question(QuestionType.MATCHING, mapOf("left" to left, "right" to right, "pairs" to mapOf("1" to "x", "2" to "y", "3" to "z", "4" to "w")))
        assertThat(
            Grader.grade(q, AnswerInput("q1", pairs = mapOf("1" to "x", "2" to "y", "3" to "z", "4" to "w")), emptyMap()),
        ).isEqualTo(AnswerResult.CORRECT)
        assertThat(
            Grader.grade(q, AnswerInput("q1", pairs = mapOf("1" to "x", "2" to "y", "3" to "w", "4" to "z")), emptyMap()),
        ).isEqualTo(AnswerResult.PARTIAL)
        assertThat(Grader.grade(q, AnswerInput("q1", pairs = mapOf("1" to "x", "2" to "v")), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
    }

    @Test
    fun `ordering needs every item once and gives partial credit for half the positions`() {
        val items = listOf("a", "b", "c", "d").map { mapOf("id" to it, "text" to mapOf("es" to it, "en" to it)) }
        val q = question(QuestionType.ORDERING, mapOf("items" to items, "order" to listOf("c", "a", "d", "b")))
        assertThat(Grader.grade(q, AnswerInput("q1", order = listOf("c", "a", "d", "b")), emptyMap())).isEqualTo(AnswerResult.CORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", order = listOf("c", "a", "b", "d")), emptyMap())).isEqualTo(AnswerResult.PARTIAL)
        assertThat(Grader.grade(q, AnswerInput("q1", order = listOf("a", "b", "c", "d")), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
        assertThat(Grader.grade(q, AnswerInput("q1", order = listOf("c", "a", "d")), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
    }

    @Test
    fun `an open answer is left to the evaluator unless it is empty`() {
        val q = question(QuestionType.SHORT_ANSWER, mapOf("model" to mapOf("es" to "m", "en" to "m"), "rubric" to mapOf("es" to "r", "en" to "r")))
        assertThat(Grader.grade(q, AnswerInput("q1", text = "Weil sie stolz ist"), emptyMap())).isNull()
        assertThat(Grader.grade(q, AnswerInput("q1", text = "  "), emptyMap())).isEqualTo(AnswerResult.INCORRECT)
        assertThat(Grader.grade(q, null, emptyMap())).isEqualTo(AnswerResult.INCORRECT)
    }

    @Test
    fun `an evaluation must cite the reference sentences`() {
        val request = EvaluationRequest("?", "r", "m", listOf(EvaluationRequest.Reference("p1s1", "Mia wohnt am Meer.")), "a", "de", "es")
        assertThat(AnswerEvaluator.validate(request, Evaluation(AnswerResult.CORRECT, " Bien. ", listOf("p1s1", "p1s1"))))
            .isEqualTo(Evaluation(AnswerResult.CORRECT, "Bien.", listOf("p1s1")))
        val noCitation = runCatching { AnswerEvaluator.validate(request, Evaluation(AnswerResult.CORRECT, "Bien.", emptyList())) }
        assertThat(noCitation.isFailure).isTrue()
        val invented = runCatching { AnswerEvaluator.validate(request, Evaluation(AnswerResult.CORRECT, "Bien.", listOf("p9s9"))) }
        assertThat(invented.isFailure).isTrue()
        val pending = runCatching { AnswerEvaluator.validate(request, Evaluation(AnswerResult.PENDING, "Bien.", listOf("p1s1"))) }
        assertThat(pending.isFailure).isTrue()
    }
}
