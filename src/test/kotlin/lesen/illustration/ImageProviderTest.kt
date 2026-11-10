package lesen.illustration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.Base64

// los proveedores reales contra un servidor HTTP de la prueba: lo que mandan y cómo leen lo que vuelve
class ImageProviderTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val b64 = Base64.getEncoder().encodeToString(png)
    private var server: DisposableServer? = null
    private val seen = mutableListOf<Triple<String, String, String>>()

    private fun serve(answer: (path: String) -> Pair<Int, String>): String {
        server =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                        val header = request.requestHeaders().get("Authorization") ?: request.requestHeaders().get("x-goog-api-key") ?: ""
                        synchronized(seen) { seen += Triple(request.uri(), header, body) }
                        val (status, text) = answer(request.uri())
                        response
                            .status(status)
                            .header("Content-Type", "application/json")
                            .sendString(Mono.just(text))
                            .then()
                    }
                }.bindNow()
        return "http://127.0.0.1:${server!!.port()}"
    }

    @AfterEach
    fun stop() {
        server?.disposeNow()
    }

    @Test
    fun `openai draws a new image, or edits from the references`() =
        runBlocking<Unit> {
            val base = serve { 200 to """{"data":[{"b64_json":"$b64"}]}""" }
            val provider = OpenAiImageProvider("sk-test", "gpt-image-1", base, Duration.ofSeconds(5))
            val drawn = provider.render("a fox", emptyList(), Aspect.PORTRAIT) as Rendered.Image
            assertThat(drawn.picture.bytes).isEqualTo(png)
            assertThat(seen[0].first).isEqualTo("/v1/images/generations")
            assertThat(seen[0].second).isEqualTo("Bearer sk-test")
            assertThat(seen[0].third).contains("\"size\":\"1024x1536\"", "a fox")
            provider.render("the fox again", listOf(Picture("image/png", png)), Aspect.LANDSCAPE)
            assertThat(seen[1].first).isEqualTo("/v1/images/edits")
            assertThat(seen[1].third).contains("name=\"image[]\"", "1536x1024")
        }

    @Test
    fun `gemini sends the references in the same message and reads the image or the refusal`() =
        runBlocking<Unit> {
            var blocked = false
            val base =
                serve {
                    if (blocked) {
                        200 to """{"promptFeedback":{"blockReason":"SAFETY"}}"""
                    } else {
                        200 to """{"candidates":[{"content":{"parts":[{"text":"ok"},{"inlineData":{"mimeType":"image/png","data":"$b64"}}]}}]}"""
                    }
                }
            val provider = GeminiImageProvider("g-key", "gemini-2.5-flash-image", base, Duration.ofSeconds(5))
            val drawn = provider.render("a fox", listOf(Picture("image/png", png)), Aspect.PANORAMA) as Rendered.Image
            assertThat(drawn.picture.contentType).isEqualTo("image/png")
            assertThat(seen[0].first).isEqualTo("/v1beta/models/gemini-2.5-flash-image:generateContent")
            assertThat(seen[0].second).isEqualTo("g-key")
            assertThat(seen[0].third).contains("inline_data", "\"aspectRatio\":\"21:9\"", "IMAGE")
            blocked = true
            assertThat(provider.render("a fox", emptyList(), Aspect.SQUARE)).isEqualTo(Rendered.Refused("SAFETY"))
        }

    @Test
    fun `a provider error is a failure of that page, never an invented image`() =
        runBlocking<Unit> {
            val base = serve { 500 to """{"error":"boom"}""" }
            val result = OpenAiImageProvider("sk-test", "gpt-image-1", base, Duration.ofSeconds(5)).render("x", emptyList(), Aspect.SQUARE)
            assertThat(result).isEqualTo(Rendered.Failed("the image provider answered 500"))
        }
}
