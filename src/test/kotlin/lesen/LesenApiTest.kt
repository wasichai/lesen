package lesen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.concurrent.TimeUnit

// base de las pruebas de API de Lesen. Antes de la primera prueba de la JVM aprovisiona la organización sembrada con el
// script real (model/apply.py --demo) contra esta misma aplicación: el contrato que se prueba es el de producción
abstract class LesenApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var environment: Environment

    protected val json: JsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun provisionOnce() {
        synchronized(LOCK) {
            if (!provisioned) {
                val port = environment.getRequiredProperty("local.server.port")
                val process =
                    ProcessBuilder("python3", "model/apply.py", "--demo", "--url", "http://127.0.0.1:$port")
                        .directory(File(System.getProperty("user.dir")))
                        .redirectErrorStream(true)
                        .apply {
                            environment()["WASICHAI_EMAIL"] = ADMIN_EMAIL
                            environment()["WASICHAI_PASSWORD"] = ADMIN_PASSWORD
                            environment()["LESEN_DEMO_PASSWORD"] = DEMO_PASSWORD
                        }.start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor(2, TimeUnit.MINUTES)) { "model/apply.py no terminó\n$output" }
                assertEquals(0, process.exitValue(), "model/apply.py --demo\n$output")
                provisioned = true
            }
        }
    }

    protected fun lena() = bearer("lena@demo.lesen.test", DEMO_PASSWORD)

    protected fun tomas() = bearer("tomas@demo.lesen.test", DEMO_PASSWORD)

    protected fun editor() = bearer("editor@demo.lesen.test", DEMO_PASSWORD)

    protected fun redactora() = bearer("redactora@demo.lesen.test", DEMO_PASSWORD)

    protected fun get(
        path: String,
        token: String,
    ): JsonNode =
        json.readTree(
            client
                .get()
                .uri(path)
                .header("Authorization", token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    protected fun send(
        method: String,
        path: String,
        token: String,
        body: Any?,
        status: Int,
    ): JsonNode? {
        val spec =
            client
                .method(
                    org.springframework.http.HttpMethod
                        .valueOf(method),
                ).uri(path)
                .header("Authorization", token)
        val exchange = if (body == null) spec.exchange() else spec.bodyValue(body).exchange()
        val text =
            exchange
                .expectStatus()
                .isEqualTo(status)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        return text?.takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    protected fun ensureProfile(token: String) {
        send(
            "PUT",
            "/api/lesen/learner/profile",
            token,
            mapOf(
                "interfaceLanguage" to "es",
                "targetLanguage" to "de",
                "level" to "A2",
                "dailyMinutes" to 10,
                "difficulty" to "BALANCED",
                "topics" to listOf("travel"),
            ),
            200,
        )
    }

    companion object {
        const val DEMO_PASSWORD = "Lesen-integration-2026"
        private val LOCK = Any()

        @Volatile
        private var provisioned = false
    }
}
