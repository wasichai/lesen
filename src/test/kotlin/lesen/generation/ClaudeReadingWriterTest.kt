package lesen.generation

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import lesen.common.LesenProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

// el escritor contra un doble local de la API de mensajes en streaming: qué se envía (modelo, esfuerzo, esquema, caché,
// el perfil sin datos personales), cómo se arma la respuesta por eventos, la corrección y la negativa
class ClaudeReadingWriterTest {
    private val json = JsonMapper.builder().build()
    private val received = mutableListOf<String>()
    private var status = 200
    private var events: List<Map<String, Any?>> = emptyList()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { exchange ->
                received += exchange.requestBody.readAllBytes().decodeToString()
                val body =
                    if (status != 200) {
                        """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
                    } else {
                        events.joinToString("") { "event: ${it["type"]}\ndata: ${json.writeValueAsString(it)}\n\n" }
                    }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private val writer =
        ClaudeReadingWriter(LesenProperties(ai = LesenProperties.Ai(apiKey = "test-key", baseUrl = "http://127.0.0.1:${server.address.port}")))

    private val context =
        GenerationContext(
            request = GenerationRequest("de", "A2", 5, TextKind.LETTER, prompt = "Ein Brief an meine Oma"),
            wordsPerMinute = 75,
            translateInto = listOf("es", "en"),
            questionLanguages = listOf("es", "en"),
            topics = listOf("alltag"),
        )

    private val output =
        """{"title":"Liebe Oma","subtitle":null,"summary":"Ein Brief.","topics":["alltag"],"names":[],""" +
            """"paragraphs":[{"sentences":[{"text":"Liebe Oma!","translations":{"es":"¡Querida abuela!","en":"Dear Grandma!"}}]}],"questions":[]}"""

    private fun stream(
        text: String,
        stop: String = "end_turn",
    ): List<Map<String, Any?>> {
        val usage = mapOf("input_tokens" to 900, "output_tokens" to 1, "cache_read_input_tokens" to 700, "cache_creation_input_tokens" to 0)
        return listOf(
            mapOf(
                "type" to "message_start",
                "message" to
                    mapOf(
                        "id" to "msg_1",
                        "type" to "message",
                        "role" to "assistant",
                        "model" to "claude-opus-5-5",
                        "content" to emptyList<Any>(),
                        "stop_reason" to null,
                        "stop_sequence" to null,
                        "usage" to usage,
                    ),
            ),
            mapOf("type" to "content_block_start", "index" to 0, "content_block" to mapOf("type" to "text", "text" to "")),
            mapOf("type" to "content_block_delta", "index" to 0, "delta" to mapOf("type" to "text_delta", "text" to text.take(40))),
            mapOf("type" to "content_block_delta", "index" to 0, "delta" to mapOf("type" to "text_delta", "text" to text.drop(40))),
            mapOf("type" to "content_block_stop", "index" to 0),
            mapOf("type" to "message_delta", "delta" to mapOf("stop_reason" to stop, "stop_sequence" to null), "usage" to mapOf("output_tokens" to 300)),
            mapOf("type" to "message_stop"),
        )
    }

    @AfterEach
    fun stop() {
        writer.close()
        server.stop(0)
    }

    @Test
    fun `streams the reading with the schema, the cached prefix and the profile, and reads its usage`() {
        events = stream(output)
        val written = runBlocking { writer.start(context).write() }
        assertThat(written).isInstanceOf(Written.Reading::class.java)
        written as Written.Reading
        assertThat(written.reading.title).isEqualTo("Liebe Oma")
        assertThat(
            written.reading.paragraphs[0]
                .sentences[0]
                .translations,
        ).containsEntry("es", "¡Querida abuela!")
        assertThat(written.usage).isEqualTo(TokenUsage(900, 300, 700, 0))
        val body = json.readTree(received.single())
        assertThat(body["model"].asString()).isEqualTo("claude-opus-5-5")
        assertThat(body["stream"].asBoolean()).isTrue()
        assertThat(body["output_config"]["effort"].asString()).isEqualTo("medium")
        assertThat(body["output_config"]["format"]["schema"]["properties"]["paragraphs"]).isNotNull()
        assertThat(body["cache_control"]["type"].asString()).isEqualTo("ephemeral")
        val text = body["messages"][0]["content"].let { if (it.isString) it.asString() else it[0]["text"].asString() }
        assertThat(text).contains("Level: A2", "Kind of text: letter", "<learner_request>\nEin Brief an meine Oma")
    }

    @Test
    fun `a correction resends the whole conversation with the findings`() {
        events = stream(output)
        val session = writer.start(context)
        runBlocking { session.write() }
        runBlocking { session.correct(listOf(Finding("LENGTH", null, "8 words; write between 281 and 469"))) }
        val body = json.readTree(received.last())
        val roles = body["messages"].values().map { it["role"].asString() }
        assertThat(roles).containsExactly("user", "assistant", "user")
        assertThat(body["messages"][2].toString()).contains("LENGTH: 8 words; write between 281 and 469")
    }

    @Test
    fun `a refusal, a cut output and an api error are not readings`() {
        events = stream("", stop = "refusal")
        assertThat(runBlocking { writer.start(context).write() }).isInstanceOf(Written.Refused::class.java)
        events = stream(output.take(30), stop = "max_tokens")
        assertThat(runBlocking { writer.start(context).write() }).isInstanceOf(Written.Unreadable::class.java)
        events = stream("{\"title\":1}")
        assertThat(runBlocking { writer.start(context).write() }).isInstanceOf(Written.Unreadable::class.java)
        status = 529
        val error = runCatching { runBlocking { writer.start(context).write() } }.exceptionOrNull()
        assertThat(error).hasMessageContaining("529")
    }

    @Test
    fun `without a key there is no writer`() {
        assertThat(ClaudeReadingWriter(LesenProperties()).available).isFalse()
        assertThat(writer.available).isTrue()
    }
}
