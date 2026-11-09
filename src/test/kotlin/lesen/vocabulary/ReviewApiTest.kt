package lesen.vocabulary

import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

// guardar una palabra, repasarla y ver cómo cambia su próxima fecha; terminar una lectura anota lo encontrado sin
// tocar el cuaderno
class ReviewApiTest : LesenApiTest() {
    // la palabra recién guardada, aunque una ejecución anterior la dejara repasada
    private fun fresh(
        token: String,
        key: String,
    ): tools.jackson.databind.JsonNode {
        val body =
            client
                .post()
                .uri("/api/lesen/vocabulary")
                .header("Authorization", token)
                .bodyValue(mapOf("entryKey" to key))
                .exchange()
                .expectStatus()
                .is2xxSuccessful
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        send("DELETE", "/api/lesen/vocabulary/${json.readTree(body)["id"].asString()}", token, null, 204)
        return send("POST", "/api/lesen/vocabulary", token, mapOf("entryKey" to key), 201)!!
    }

    @Test
    fun `a review moves the next date of the word and only the review moves its status`() {
        val token = tomas()
        ensureProfile(token)
        val saved = fresh(token, "de:leuchtturm")
        val id = saved["id"].asString()
        assertThat(saved["status"].asString()).isEqualTo("SAVED")

        val queue = get("/api/lesen/vocabulary/reviews/next?language=de", token)
        val card = queue["cards"].values().first { it["item"]["id"].asString() == id }
        assertThat(card["state"].asString()).isEqualTo("LEARNING")
        val previews = listOf("AGAIN", "HARD", "GOOD", "EASY").map { Instant.parse(card["preview"][it].asString()) }
        assertThat(previews).isSorted

        val before = Instant.parse(card["item"]["dueAt"].asString())
        val client = UUID.randomUUID().toString()
        val easy = send("POST", "/api/lesen/vocabulary/reviews", token, mapOf("vocabularyId" to id, "rating" to "EASY", "clientId" to client), 200)!!
        assertThat(easy["previousStatus"].asString()).isEqualTo("SAVED")
        assertThat(easy["item"]["status"].asString()).isEqualTo("FAMILIAR")
        val due = Instant.parse(easy["item"]["dueAt"].asString())
        // FSRS-5: Fácil en una palabra nueva la manda a 16 días
        assertThat(due).isAfter(before.plusSeconds(15 * 86_400))
        assertThat(easy["item"]["reviews"].asInt()).isEqualTo(1)

        // el mismo repaso repetido no cuenta dos veces
        val again = send("POST", "/api/lesen/vocabulary/reviews", token, mapOf("vocabularyId" to id, "rating" to "EASY", "clientId" to client), 200)!!
        assertThat(again["item"]["dueAt"].asString()).isEqualTo(easy["item"]["dueAt"].asString())
        assertThat(again["item"]["reviews"].asInt()).isEqualTo(1)

        // ya no toca hoy
        assertThat(get("/api/lesen/vocabulary/reviews/next?language=de", token)["cards"].values().none { it["item"]["id"].asString() == id }).isTrue()
        val stats = get("/api/lesen/vocabulary/stats?language=de", token)
        assertThat(stats["byStatus"]["FAMILIAR"].asInt()).isGreaterThanOrEqualTo(1)
        assertThat(stats["reviewedToday"].asInt()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `reviews are personal and validated`() {
        val token = tomas()
        val saved = fresh(token, "de:meer")
        val id = saved["id"].asString()
        send("POST", "/api/lesen/vocabulary/reviews", lena(), mapOf("vocabularyId" to id, "rating" to "GOOD", "clientId" to "x1"), 404)
        send("POST", "/api/lesen/vocabulary/reviews", token, mapOf("vocabularyId" to id, "rating" to "GOOD", "clientId" to ""), 400)
        send("POST", "/api/lesen/vocabulary/reviews", token, mapOf("vocabularyId" to id, "rating" to "PERFECT", "clientId" to "x2"), 400)
        send("POST", "/api/lesen/vocabulary/reviews", editor(), mapOf("vocabularyId" to id, "rating" to "GOOD", "clientId" to "x3"), 403)
        send("GET", "/api/lesen/vocabulary/reviews/next?limit=500", token, null, 400)
    }

    @Test
    fun `finishing a reading records what was encountered without saving anything`() {
        val token = lena()
        ensureProfile(token)
        val before = get("/api/lesen/vocabulary/known?language=de", token)
        val savedBefore = before["saved"].size()
        send("POST", "/api/lesen/readings/der-fuchs-im-garten/completion", token, mapOf("version" to 1, "secondsRead" to 120), 200)
        val after = get("/api/lesen/vocabulary/known?language=de", token)
        val encountered = after["encountered"].values().map { it.asString() }
        assertThat(encountered).contains("de:fuchs", "de:garten")
        assertThat(after["saved"].size()).isEqualTo(savedBefore)
        // terminarla otra vez no duplica nada
        send("POST", "/api/lesen/readings/der-fuchs-im-garten/completion", token, mapOf("version" to 1, "secondsRead" to 130), 200)
        assertThat(get("/api/lesen/vocabulary/known?language=de", token)["encountered"].size()).isEqualTo(encountered.size)
        // quien solo lee el catálogo no tiene cuaderno
        send("GET", "/api/lesen/vocabulary/known?language=de", editor(), null, 403)
    }
}
