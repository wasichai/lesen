package lesen

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.ReadingPublisher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient

class LesenApiIntegrationTest : LesenApiTest() {
    @Autowired
    private lateinit var publisher: ReadingPublisher

    @Autowired
    private lateinit var db: DatabaseClient

    @Test
    fun `public catalog data is anonymous, everything else needs a token`() {
        client
            .get()
            .uri("/api/lesen/public/languages")
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/api/lesen/public/topics")
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/api/lesen/public/covers/der-alte-leuchtturm.svg")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType("image/svg+xml")
        client
            .get()
            .uri("/api/lesen/public/covers/..%2Fsecret.svg")
            .exchange()
            .expectStatus()
            .is4xxClientError
        client
            .get()
            .uri("/api/lesen/public/covers/missing.svg")
            .exchange()
            .expectStatus()
            .isNotFound
        client
            .get()
            .uri("/api/lesen/readings")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/api/lesen/learner/profile")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `profile round trip, validation, and a role without notebook rights is refused`() {
        val token = lena()
        val saved =
            send(
                "PUT",
                "/api/lesen/learner/profile",
                token,
                mapOf(
                    "interfaceLanguage" to "es",
                    "targetLanguage" to "de",
                    "level" to "B1",
                    "goals" to listOf("TRAVEL"),
                    "topics" to listOf("nature"),
                    "dailyMinutes" to 15,
                    "difficulty" to "CHALLENGING",
                ),
                200,
            )!!
        assertThat(saved["level"].asString()).isEqualTo("B1")
        assertThat(get("/api/lesen/learner/profile", token)["topics"].values().map { it.asString() }).containsExactly("nature")
        val invalid =
            send(
                "PUT",
                "/api/lesen/learner/profile",
                token,
                mapOf("interfaceLanguage" to "de", "targetLanguage" to "de", "level" to "Z9", "dailyMinutes" to 1),
                400,
            )!!
        assertThat(invalid["errors"].values().map { it["field"].asString() }).contains("interfaceLanguage", "targetLanguage", "level", "dailyMinutes")
        send("PUT", "/api/lesen/learner/profile", editor(), mapOf("interfaceLanguage" to "es", "targetLanguage" to "de", "level" to "A1"), 403)
    }

    @Test
    fun `catalog filters by language, level and topic subtree, and discover explains recommendations`() {
        val token = tomas()
        ensureProfile(token)
        val travel = get("/api/lesen/readings?language=de&level=A2&topic=travel", token)
        assertThat(travel["content"].values().map { it["slug"].asString() }).containsExactly("der-alte-leuchtturm")
        val english = get("/api/lesen/readings?language=en&size=50", token)
        assertThat(english["totalElements"].asInt()).isEqualTo(3)
        val discover = get("/api/lesen/discover", token)
        assertThat(discover["recommended"].values().map { it["reading"]["language"].asString() }).allMatch { it == "de" }
        assertThat(discover["recommended"].first()["reasons"].values().map { it.asString() }).contains("LEVEL")
        send("GET", "/api/lesen/readings?size=99", token, null, 400)
    }

    @Test
    fun `content is immutable and cacheable, lookup is contextual, missing translations are 404`() {
        val token = tomas()
        ensureProfile(token)
        client
            .get()
            .uri("/api/lesen/readings/der-alte-leuchtturm/versions/1/content")
            .header("Authorization", token)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueMatches("Cache-Control", ".*immutable.*")
        val lookup = get("/api/lesen/dictionary/lookup?reading=der-alte-leuchtturm&version=1&token=p1s1t2", token)
        assertThat(lookup["token"].asString()).isEqualTo("verbringt")
        assertThat(lookup["entry"]["lemma"].asString()).isEqualTo("verbringen")
        assertThat(lookup["inflected"].asBoolean()).isTrue()
        assertThat(lookup["entry"]["senses"][0]["translations"].size()).isPositive()
        val sentence = get("/api/lesen/readings/der-alte-leuchtturm/versions/1/translations?unit=SENTENCE&ref=p1s1", token)
        assertThat(sentence[0]["language"].asString()).isEqualTo("es")
        send("GET", "/api/lesen/readings/der-alte-leuchtturm/versions/1/translations?unit=SENTENCE&ref=p99s1", token, null, 404)
        val search = get("/api/lesen/dictionary/search?q=faro", token)
        assertThat(search.values().map { it["key"].asString() }).contains("de:leuchtturm")
    }

    @Test
    fun `saving a word is idempotent and its context comes from the published text`() {
        val token = lena()
        ensureProfile(token)
        val body = mapOf("entryKey" to "de:verbringen", "reading" to "der-alte-leuchtturm", "version" to 1, "tokenRef" to "p1s1t2")
        val first = send("POST", "/api/lesen/vocabulary", token, body, 201)!!
        assertThat(first["surface"].asString()).isEqualTo("verbringt")
        assertThat(first["context"].asString()).startsWith("Mia verbringt")
        assertThat(first["status"].asString()).isEqualTo("SAVED")
        send("POST", "/api/lesen/vocabulary", token, body, 200)
        send("POST", "/api/lesen/vocabulary", token, body + ("tokenRef" to "p1s1t1"), 400)
        assertThat(get("/api/lesen/vocabulary/keys?language=de", token).values().map { it.asString() }).contains("de:verbringen")
        // nadie más ve ni borra lo de otra persona
        assertThat(get("/api/lesen/vocabulary/keys?language=de", tomas()).values().map { it.asString() }).doesNotContain("de:verbringen")
        send("DELETE", "/api/lesen/vocabulary/${first["id"].asString()}", tomas(), null, 404)
        send("DELETE", "/api/lesen/vocabulary/${first["id"].asString()}", token, null, 204)
    }

    @Test
    fun `progress is idempotent, completion sticks, and notes are private`() {
        val token = tomas()
        ensureProfile(token)
        val report = mapOf("version" to 1, "position" to "p2s1", "percent" to 40, "secondsRead" to 60)
        val once = send("PUT", "/api/lesen/readings/der-fuchs-im-garten/progress", token, report, 200)!!
        val twice = send("PUT", "/api/lesen/readings/der-fuchs-im-garten/progress", token, report, 200)!!
        assertThat(twice["percent"].asInt()).isEqualTo(once["percent"].asInt())
        assertThat(twice["secondsRead"].asInt()).isEqualTo(60)
        send("PUT", "/api/lesen/readings/der-fuchs-im-garten/progress", token, report + ("position" to "p99s9"), 400)
        val done = send("POST", "/api/lesen/readings/der-fuchs-im-garten/completion", token, mapOf("version" to 1, "secondsRead" to 90), 200)!!
        assertThat(done["completed"].asBoolean()).isTrue()
        assertThat(done["percent"].asInt()).isEqualTo(100)
        assertThat(get("/api/lesen/library", token)["completed"].values().map { it["slug"].asString() }).contains("der-fuchs-im-garten")
        val note =
            send(
                "POST",
                "/api/lesen/readings/der-fuchs-im-garten/notes",
                token,
                mapOf("version" to 1, "sentenceRef" to "p1s1", "note" to "schön"),
                201,
            )!!
        assertThat(note["quote"].asString()).isNotBlank()
        send("DELETE", "/api/lesen/notes/${note["id"].asString()}", lena(), null, 404)
        send("DELETE", "/api/lesen/notes/${note["id"].asString()}", token, null, 204)
    }

    @Test
    fun `an edited reading becomes version 2 while version 1 stays readable and unchanged`() {
        runBlocking {
            val token = lena()
            ensureProfile(token)
            send(
                "PUT",
                "/api/lesen/readings/ein-sonntag-bei-oma/progress",
                token,
                mapOf(
                    "version" to 1,
                    "position" to "p1s1",
                    "percent" to 10,
                    "secondsRead" to 5,
                ),
                200,
            )
            val original = Jsonb.read<ReadingDraft>(ClassLoader.getSystemResource("demo/readings/ein-sonntag-bei-oma.json").readText())
            val edited = original.copy(subtitle = "Neu erzählt")
            assertThat(publisher.publish(original).created).isFalse()
            val result = publisher.publish(edited)
            assertThat(result.created).isTrue()
            assertThat(result.version).isEqualTo(2)
            assertThat(get("/api/lesen/readings/ein-sonntag-bei-oma", token)["reading"]["version"].asInt()).isEqualTo(2)
            assertThat(get("/api/lesen/readings/ein-sonntag-bei-oma/progress", token)["version"].asInt()).isEqualTo(1)
            assertThat(get("/api/lesen/readings/ein-sonntag-bei-oma/versions/1/content", token)["current"].asBoolean()).isFalse()
            assertThatThrownBy {
                runBlocking {
                    db
                        .sql(
                            "UPDATE lesen.reading_version SET title = 'x' WHERE number = 1 AND reading_id = (SELECT id FROM lesen.reading WHERE slug = 'ein-sonntag-bei-oma')",
                        ).fetch()
                        .rowsUpdated()
                        .awaitSingle()
                }
            }.hasMessageContaining("inmutable")
            assertThat(ReadingAssembler.hash(edited)).isNotEqualTo(ReadingAssembler.hash(original))
        }
    }

    private object Jsonb {
        inline fun <reified T> read(text: String): T = lesen.common.Jsonb.read(text)
    }
}
