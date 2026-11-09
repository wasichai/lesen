package lesen.assessment

import com.sun.net.httpserver.HttpServer
import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

// con asistente (aquí, un doble de la API de mensajes): la respuesta abierta queda PENDING, la cola la corrige y el
// intento vuelve con el resultado, la justificación y las oraciones citadas. Si el asistente cita algo que no se le dio,
// la respuesta no se da por corregida
class AssistedEvaluationApiTest : LesenApiTest() {
    @Test
    fun `the queue grades an open answer with a cited justification`() {
        val token = lena()
        ensureProfile(token)
        val id = get("/api/lesen/readings/der-alte-leuchtturm/versions/1/assessment", token)["id"].asString()
        val attempt =
            send(
                "POST",
                "/api/lesen/assessments/$id/attempts",
                token,
                mapOf("answers" to listOf(mapOf("questionId" to "q7", "text" to "Sie hat geholfen."))),
                201,
            )!!
        assertThat(attempt["answers"].values().first { it["questionId"].asString() == "q7" }["result"].asString()).isEqualTo("PENDING")
        assertThat(attempt["summary"]["pending"].asInt()).isEqualTo(1)

        val graded = waitFor(attempt["id"].asString(), token)
        assertThat(graded["result"].asString()).isEqualTo("PARTIAL")
        assertThat(graded["evaluator"].asString()).isEqualTo("AI")
        assertThat(graded["justification"].asString()).isEqualTo(JUSTIFICATION)
        assertThat(graded["citations"].values().map { it.asString() }).isNotEmpty
        // lo que recibió el asistente: la respuesta, la rúbrica y las oraciones, nada de quién responde
        assertThat(lastRequest).contains("Sie hat geholfen.", "<rubric>").doesNotContain("lena@", "Lena Lectora")
    }

    private fun waitFor(
        attemptId: String,
        token: String,
    ): tools.jackson.databind.JsonNode {
        repeat(100) {
            val answer = get("/api/lesen/attempts/$attemptId", token)["answers"].values().first { it["questionId"].asString() == "q7" }
            if (answer["result"].asString() != "PENDING") return answer
            Thread.sleep(100)
        }
        error("the open answer is still pending")
    }

    companion object {
        const val JUSTIFICATION = "Vas bien, pero falta decir que encendió la luz de reserva."
        private val json = JsonMapper.builder().build()

        @Volatile
        var lastRequest = ""

        // cita la primera oración que se le da, como haría un corrector que lee las referencias
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/messages") { exchange ->
                    val body = exchange.requestBody.readAllBytes().decodeToString()
                    val text = json.readTree(body)["messages"][0]["content"].let { if (it.isString) it.asString() else it[0]["text"].asString() }
                    lastRequest = text
                    val cited = Regex("\\[(p\\d+s\\d+)]").find(text)!!.groupValues[1]
                    val output = json.writeValueAsString(mapOf("result" to "PARTIAL", "justification" to JUSTIFICATION, "citations" to listOf(cited)))
                    val reply =
                        json.writeValueAsString(
                            mapOf(
                                "id" to "msg_1",
                                "type" to "message",
                                "role" to "assistant",
                                "model" to "claude-opus-5-5",
                                "content" to listOf(mapOf("type" to "text", "text" to output)),
                                "stop_reason" to "end_turn",
                                "usage" to mapOf("input_tokens" to 1, "output_tokens" to 1),
                            ),
                        )
                    val bytes = reply.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun assistant(registry: DynamicPropertyRegistry) {
            registry.add("lesen.ai.api-key") { "test-key" }
            registry.add("lesen.ai.base-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("lesen.ai.poll-millis") { 100 }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }
}
