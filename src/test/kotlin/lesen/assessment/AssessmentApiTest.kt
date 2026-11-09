package lesen.assessment

import lesen.LesenApiTest
import lesen.common.Jsonb
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// las preguntas de der-alte-leuchtturm (siete, una por tipo distinto) contra la API: lo que se ve antes de responder, la
// corrección de lo cerrado, la autoevaluación de lo abierto sin asistente y quién puede ver qué
class AssessmentApiTest : LesenApiTest() {
    private val seed: AssessmentSeed =
        Jsonb.read(javaClass.getResource("/demo/assessments/der-alte-leuchtturm.json")!!.readText())

    private fun path() = "/api/lesen/readings/der-alte-leuchtturm/versions/1/assessment"

    // las respuestas correctas de lo cerrado, sacadas del origen; la abierta, con texto
    private fun rightAnswers(words: Map<String, String>): List<Map<String, Any>> =
        seed.questions.map { q ->
            when (val spec = q.parsed()) {
                is ChoiceSpec -> mapOf("questionId" to q.id, "options" to spec.correct)
                is TrueFalseSpec -> mapOf("questionId" to q.id, "value" to spec.correct)
                is CompletionSpec -> mapOf("questionId" to q.id, "blanks" to spec.blanks.associateWith { words.getValue(it).uppercase() + "." })
                is MatchingSpec -> mapOf("questionId" to q.id, "pairs" to spec.pairs)
                is OrderingSpec -> mapOf("questionId" to q.id, "order" to spec.order)
                is ShortAnswerSpec -> mapOf("questionId" to q.id, "text" to "Weil sie das Licht angemacht hat und das Schiff sicher ankommt.")
            }
        }

    private fun words(token: String): Map<String, String> {
        val content = get("/api/lesen/readings/der-alte-leuchtturm/versions/1/content", token)
        return content["paragraphs"]
            .values()
            .flatMap { p -> p["sentences"].values().flatMap { s -> s["tokens"].values() } }
            .associate { it["id"].asString() to it["text"].asString() }
    }

    @Test
    fun `the questions come in the interface language and never carry their answers`() {
        val token = lena()
        ensureProfile(token)
        val assessment = get(path(), token)
        val types = assessment["questions"].values().map { it["type"].asString() }
        assertThat(types).containsExactly("DETAIL", "TRUE_FALSE", "VOCABULARY_IN_CONTEXT", "COMPLETION", "MATCHING", "ORDERING", "SHORT_ANSWER")
        assertThat(assessment["language"].asString()).isEqualTo("de")
        assertThat(assessment["questions"][0]["prompt"].asString()).isEqualTo(seed.questions[0].prompt["es"])
        val text = assessment["questions"].toString()
        assertThat(text).doesNotContain("\"correct\"", "\"solution\"", "\"pairs\"", "\"order\"", "\"model\"", "\"rubric\"")
        val completion = assessment["questions"][3]["completion"]
        assertThat(
            completion["parts"].values().count { it.has("blank") && !it["blank"].isNull },
        ).isEqualTo((seed.questions[3].parsed() as CompletionSpec).blanks.size)
        val vocabulary = assessment["questions"][2]["word"]
        assertThat(vocabulary["id"].asString()).isEqualTo((seed.questions[2].parsed() as ChoiceSpec).token)
        assertThat(assessment["questions"][4]["right"].size()).isEqualTo((seed.questions[4].parsed() as MatchingSpec).right.size)
        // en inglés si se pide
        assertThat(get("${path()}?language=en", token)["questions"][0]["prompt"].asString()).isEqualTo(seed.questions[0].prompt["en"])
    }

    @Test
    fun `closed questions are graded at once and the open one waits for a self-assessment`() {
        val token = lena()
        ensureProfile(token)
        val id = get(path(), token)["id"].asString()
        val attempt = send("POST", "/api/lesen/assessments/$id/attempts", token, mapOf("answers" to rightAnswers(words(token))), 201)!!
        val answers = attempt["answers"].values().associateBy { it["questionId"].asString() }
        for (q in seed.questions.filter { it.type != QuestionType.SHORT_ANSWER }) {
            assertThat(answers.getValue(q.id)["result"].asString()).describedAs(q.id).isEqualTo("CORRECT")
            assertThat(answers.getValue(q.id)["evaluator"].asString()).isEqualTo("RULE")
            assertThat(answers.getValue(q.id)["explanation"].asString()).isEqualTo(q.explanation["es"])
        }
        // sin clave de asistente en las pruebas: la abierta queda para autoevaluar, con la respuesta modelo a la vista
        val open = seed.questions.first { it.type == QuestionType.SHORT_ANSWER }
        assertThat(answers.getValue(open.id)["result"].asString()).isEqualTo("UNGRADED")
        assertThat(answers.getValue(open.id)["solution"]["text"].asString()).isEqualTo((open.parsed() as ShortAnswerSpec).model["es"])
        assertThat(attempt["summary"]["correct"].asInt()).isEqualTo(6)
        assertThat(attempt["summary"]["ungraded"].asInt()).isEqualTo(1)

        val attemptId = attempt["id"].asString()
        val graded = send("POST", "/api/lesen/attempts/$attemptId/answers/${open.id}/self-assessment", token, mapOf("result" to "PARTIAL"), 200)!!
        val self = graded["answers"].values().first { it["questionId"].asString() == open.id }
        assertThat(self["result"].asString()).isEqualTo("PARTIAL")
        assertThat(self["evaluator"].asString()).isEqualTo("SELF")
        // una sola vez, y nunca sobre lo que corrigió la regla
        send("POST", "/api/lesen/attempts/$attemptId/answers/${open.id}/self-assessment", token, mapOf("result" to "CORRECT"), 409)
        send("POST", "/api/lesen/attempts/$attemptId/answers/q1/self-assessment", token, mapOf("result" to "INCORRECT"), 409)
        send("POST", "/api/lesen/attempts/$attemptId/answers/${open.id}/self-assessment", token, mapOf("result" to "PENDING"), 400)

        // el resumen del último intento vuelve con las preguntas
        val last = get(path(), token)["lastAttempt"]
        assertThat(last["id"].asString()).isEqualTo(attemptId)
        assertThat(last["partial"].asInt()).isEqualTo(1)
    }

    @Test
    fun `wrong and missing answers are wrong, never an error`() {
        val token = lena()
        val id = get(path(), token)["id"].asString()
        val attempt =
            send(
                "POST",
                "/api/lesen/assessments/$id/attempts",
                token,
                mapOf("answers" to listOf(mapOf("questionId" to "q2", "value" to !(seed.questions[1].parsed() as TrueFalseSpec).correct))),
                201,
            )!!
        assertThat(attempt["answers"].size()).isEqualTo(seed.questions.size)
        assertThat(attempt["answers"].values().map { it["result"].asString() }.toSet()).containsExactly("INCORRECT")
        send("POST", "/api/lesen/assessments/$id/attempts", token, mapOf("answers" to listOf(mapOf("questionId" to "q99", "value" to true))), 400)
        send("POST", "/api/lesen/assessments/$id/attempts", token, mapOf("answers" to listOf(mapOf("questionId" to "q1"), mapOf("questionId" to "q1"))), 400)
        send("POST", "/api/lesen/assessments/$id/attempts", token, mapOf("answers" to listOf(mapOf("questionId" to "q7", "text" to "x".repeat(2001)))), 400)
    }

    @Test
    fun `an attempt belongs to whoever made it`() {
        val id = get(path(), lena())["id"].asString()
        val attempt = send("POST", "/api/lesen/assessments/$id/attempts", lena(), mapOf("answers" to emptyList<Any>()), 201)!!
        val attemptId = attempt["id"].asString()
        send("GET", "/api/lesen/attempts/$attemptId", tomas(), null, 404)
        send("POST", "/api/lesen/attempts/$attemptId/answers/q7/self-assessment", tomas(), mapOf("result" to "CORRECT"), 404)
        // quien solo lee el catálogo ve las preguntas, sin resumen personal, pero no puede responder
        val forEditor = get(path(), editor())
        assertThat(forEditor["lastAttempt"].isNull).isTrue()
        send("POST", "/api/lesen/assessments/$id/attempts", editor(), mapOf("answers" to emptyList<Any>()), 403)
        client
            .get()
            .uri(path())
            .exchange()
            .expectStatus()
            .isUnauthorized
        send("GET", "/api/lesen/readings/der-alte-leuchtturm/versions/99/assessment", lena(), null, 404)
    }
}
