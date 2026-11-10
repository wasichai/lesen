package lesen.commission

import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

// una lectura a medida (ADR-0016): quien aprende pide un libro ilustrado, lo lee con el lector de siempre y nadie más lo
// encuentra; la cuota se dice y se cumple. El escritor es el guion de CommissionApiTest y las imágenes, las de prueba
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = [
        "lesen.illustration.provider=fake",
        "lesen.generation.poll-millis=200", "lesen.generation.worker=true",
        "lesen.generation.quota.daily=1",
        "lesen.generation.quota.monthly-books=5",
    ],
)
@Import(CommissionApiTest.ScriptedWriter::class)
class PrivateReadingApiTest : LesenApiTest() {
    private fun job(
        token: String,
        id: String,
    ): JsonNode {
        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        while (true) {
            val view = get("/api/lesen/generations/$id", token)
            if (view["status"].asString() in setOf("READY", "FAILED") || Instant.now().isAfter(deadline)) return view
            Thread.sleep(250)
        }
    }

    @Test
    fun `a learner asks for a picture book, reads it, and nobody else can find it`() {
        val lena = lena()
        ensureProfile(lena)
        val before = get("/api/lesen/generations/quota", lena)
        assertThat(before["dailyLimit"].asInt()).isEqualTo(1)
        // quien no tiene el ancla lectura_a_medida no pide: la editora es del estudio, no lectora
        send("POST", "/api/lesen/generations", editor(), mapOf("language" to "de", "level" to "A1"), 403)
        send("GET", "/api/lesen/generations/quota", redactora(), null, 403)

        val queued =
            send(
                "POST",
                "/api/lesen/generations",
                lena,
                mapOf("language" to "de", "level" to "A1", "minutes" to 2, "format" to "PICTURE_BOOK", "prompt" to "Ein Sonntag bei der Oma"),
                202,
            )!!
        assertThat(queued["target"].asString()).isEqualTo("PRIVATE")
        val done = job(lena, queued["id"].asString())
        assertThat(done["status"].asString()).isEqualTo("READY")
        val slug = done["readingSlug"].asString()

        // la lee con el lector de siempre: su ficha, su contenido de libro, sus preguntas
        val detail = get("/api/lesen/readings/$slug", lena)
        assertThat(detail["reading"]["custom"].asBoolean()).isTrue()
        assertThat(detail["reading"]["format"].asString()).isEqualTo("PICTURE_BOOK")
        val version = detail["reading"]["version"].asInt()
        val content = get("/api/lesen/readings/$slug/versions/$version/content", lena)
        assertThat(content["pages"].size()).isEqualTo(5)
        assertThat(content["pages"][0]["illustration"]["src"].asString()).startsWith("/api/lesen/public/illustrations/illustration-")
        assertThat(get("/api/lesen/readings/$slug/versions/$version/assessment", lena)["questions"].size()).isEqualTo(4)
        send("PUT", "/api/lesen/readings/$slug/progress", lena, mapOf("version" to version, "position" to "p1s1", "percent" to 10, "secondsRead" to 30), 200)

        // para cualquier otra persona no existe, y no está en el catálogo de nadie
        val tomas = tomas()
        ensureProfile(tomas)
        send("GET", "/api/lesen/readings/$slug", tomas, null, 404)
        send("GET", "/api/lesen/readings/$slug/versions/$version/content", tomas, null, 404)
        send("GET", "/api/lesen/readings/$slug/versions/$version/assessment", tomas, null, 404)
        send("PUT", "/api/lesen/readings/$slug/progress", tomas, mapOf("version" to version, "position" to "p1s1", "percent" to 10, "secondsRead" to 30), 404)
        send("GET", "/api/lesen/generations/${queued["id"].asString()}", tomas, null, 404)
        assertThat(get("/api/lesen/generations", tomas).size()).isZero()
        for (token in listOf(lena, tomas)) {
            assertThat(get("/api/lesen/readings?size=50&language=de", token)["content"].values().map { it["slug"].asString() }).doesNotContain(slug)
        }
        assertThat(get("/api/lesen/studio/readings", editor()).values().map { it["slug"].asString() }).doesNotContain(slug)
        // en lo que lleva leyendo, sí: es suya
        assertThat(get("/api/lesen/readings?status=IN_PROGRESS&size=50", lena)["content"].values().map { it["slug"].asString() }).contains(slug)

        // la cuota del día está gastada: se dice cuándo vuelve
        val after = get("/api/lesen/generations/quota", lena)
        assertThat(after["dailyUsed"].asInt()).isEqualTo(1)
        assertThat(after["monthlyBooksUsed"].asInt()).isEqualTo(1)
        assertThat(after["resetsAt"].isNull).isFalse()
        send("POST", "/api/lesen/generations", lena, mapOf("language" to "de", "level" to "A1"), 429)
        assertThat(get("/api/lesen/generations", lena).values().map { it["id"].asString() }).containsExactly(queued["id"].asString())
    }
}
