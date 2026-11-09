package lesen.progress

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

// la actividad real (leer, guardar, terminar) llega al panel una sola vez, separando lo medido de lo estimado; la
// proyección se reconstruye igual; las metas, los logros y las listas son de cada persona. La base de prueba se
// reutiliza entre ejecuciones: se comparan diferencias, no valores absolutos
class ProgressApiTest : LesenApiTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private fun dashboard(token: String) = get("/api/lesen/progress/dashboard", token)

    private fun seconds(
        token: String,
        slug: String,
    ): Int =
        client
            .get()
            .uri("/api/lesen/readings/$slug/progress")
            .header("Authorization", token)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .let { if (it.status.value() == 404) 0 else json.readTree(it.responseBody)["secondsRead"].asInt() }

    private fun read(
        token: String,
        slug: String,
        extra: Int,
    ): JsonNode {
        val report = mapOf("version" to 1, "position" to "p1s1", "percent" to 30, "secondsRead" to seconds(token, slug) + extra)
        send("PUT", "/api/lesen/readings/$slug/progress", token, report, 200)
        // el mismo informe otra vez no suma nada
        return send("PUT", "/api/lesen/readings/$slug/progress", token, report, 200)!!
    }

    // que la palabra no esté en el cuaderno (guardarla si hace falta para conocer su id, y borrarla)
    private fun forget(
        token: String,
        key: String,
    ) {
        val existing =
            client
                .post()
                .uri("/api/lesen/vocabulary")
                .header("Authorization", token)
                .bodyValue(mapOf("entryKey" to key))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        send("DELETE", "/api/lesen/vocabulary/${json.readTree(existing)["id"].asString()}", token, null, 204)
    }

    // el usuario del token (la proyección se compara fila a fila en la base)
    private fun userId(token: String): UUID {
        val claims = json.readTree(Base64.getUrlDecoder().decode(token.removePrefix("Bearer ").split(".")[1]))
        val id = listOf("userId", "uid", "sub").firstNotNullOfOrNull { claims[it]?.asString()?.takeIf { v -> v.length == 36 } }
        return UUID.fromString(id ?: error("sin id de usuario en $claims"))
    }

    private fun utc(token: String) = send("PUT", "/api/lesen/progress/goals", token, mapOf("timeZone" to "UTC", "weeklyDays" to 3), 200)!!

    @Test
    fun `reading and saving words reach the dashboard once, and each number says whether it is measured or estimated`() {
        val token = lena()
        ensureProfile(token)
        utc(token)
        forget(token, "de:garten")
        val before = dashboard(token)
        val weekBefore = get("/api/lesen/progress/weekly", token)

        read(token, "der-fuchs-im-garten", 120)
        send("POST", "/api/lesen/vocabulary", token, mapOf("entryKey" to "de:garten"), 201)
        send("POST", "/api/lesen/vocabulary", token, mapOf("entryKey" to "de:garten"), 200)

        val after = dashboard(token)
        assertThat(after["reading"]["minutes"]["value"].asInt() - before["reading"]["minutes"]["value"].asInt()).isEqualTo(2)
        assertThat(after["reading"]["minutes"]["kind"].asString()).isEqualTo("MEASURED")
        assertThat(after["reading"]["wordsRead"]["kind"].asString()).isEqualTo("ESTIMATED")
        assertThat(after["vocabulary"]["mastered"]["kind"].asString()).isEqualTo("ESTIMATED")
        assertThat(after["vocabulary"]["encountered"]["kind"].asString()).isEqualTo("MEASURED")
        assertThat(after["level"]["kind"].asString()).isEqualTo("ESTIMATED")
        assertThat(after["level"]["level"].asString()).isEqualTo("A2")
        assertThat(after["goal"]["dailyMinutes"].asInt()).isEqualTo(10)
        assertThat(after["goal"]["todayMinutes"]["value"].asInt()).isGreaterThanOrEqualTo(2)
        assertThat(after["days"].size()).isEqualTo(28)
        assertThat(after["days"].last()["date"].asString()).isEqualTo(after["today"].asString())
        assertThat(after["days"].last()["active"].asBoolean()).isTrue()
        assertThat(after["vocabulary"]["growth"].size()).isEqualTo(8)
        // ninguna cifra es un fallo: no hay campos de días perdidos ni rachas rotas
        assertThat(after.toString()).doesNotContainIgnoringCase("missed").doesNotContainIgnoringCase("lost")

        val week = get("/api/lesen/progress/weekly", token)
        assertThat(week["wordsSaved"]["value"].asInt() - weekBefore["wordsSaved"]["value"].asInt()).isEqualTo(1)
        assertThat(week["minutes"]["value"].asInt() - weekBefore["minutes"]["value"].asInt()).isEqualTo(2)
        assertThat(week["days"].size()).isEqualTo(7)
        assertThat(week["current"].asBoolean()).isTrue()
        assertThat(week["activeDays"].asInt()).isGreaterThanOrEqualTo(1)
        val past = get("/api/lesen/progress/weekly?week=2026-01-07", token)
        assertThat(past["weekStart"].asString()).isEqualTo("2026-01-05")
        assertThat(past["current"].asBoolean()).isFalse()
        send("GET", "/api/lesen/progress/weekly?week=yesterday", token, null, 400)
    }

    @Test
    fun `finishing a reading counts once, earns an achievement once, and shows in what is read this week`() {
        val token = lena()
        ensureProfile(token)
        utc(token)
        send("POST", "/api/lesen/readings/der-alte-leuchtturm/completion", token, mapOf("version" to 1, "secondsRead" to 30), 200)
        val once = dashboard(token)
        send("POST", "/api/lesen/readings/der-alte-leuchtturm/completion", token, mapOf("version" to 1, "secondsRead" to 30), 200)
        val twice = dashboard(token)
        assertThat(twice["reading"]["completed"]["value"].asInt()).isEqualTo(once["reading"]["completed"]["value"].asInt()).isPositive()
        assertThat(twice["reading"]["wordsRead"]["value"].asInt()).isEqualTo(once["reading"]["wordsRead"]["value"].asInt()).isPositive()

        val achievements = get("/api/lesen/progress/achievements", token)
        assertThat(achievements.size()).isEqualTo(13)
        val first = achievements.values().first { it["code"].asString() == "FIRST_READING" }
        assertThat(first["earnedAt"].isNull).isFalse()
        assertThat(first["value"].asInt()).isEqualTo(1)
        val again = get("/api/lesen/progress/achievements", token).values().first { it["code"].asString() == "FIRST_READING" }
        assertThat(again["earnedAt"].asString()).isEqualTo(first["earnedAt"].asString())

        val discover = get("/api/lesen/discover", tomas().also { ensureProfile(it) })
        val trending = discover["thisWeek"].values().first { it["reading"]["slug"].asString() == "der-alte-leuchtturm" }
        assertThat(trending["readers"].asInt()).isPositive()
        // un agregado: ningún dato de quién la leyó
        assertThat(trending.toString()).doesNotContain("lena")
    }

    @Test
    fun `changing the time zone rebuilds the same days from the events`() {
        runBlocking {
            val token = tomas()
            ensureProfile(token)
            utc(token)
            // algo nuevo por la vía incremental antes de comparar
            read(token, "ein-sonntag-bei-oma", 90)
            val me = userId(token)

            suspend fun rows() =
                db
                    .sql(
                        "SELECT day, language, seconds, readings_started, readings_completed, words_read, words_saved, words_reviewed, assessments " +
                            "FROM lesen.daily_progress WHERE user_id = :u ORDER BY day, language",
                    ).bind("u", me)
                    .map { row, _ -> (0..8).map { row.get(it).toString() } }
                    .all()
                    .collectList()
                    .awaitSingle()

            val incremental = rows()
            assertThat(incremental).isNotEmpty()
            val far = send("PUT", "/api/lesen/progress/goals", token, mapOf("timeZone" to "Pacific/Kiritimati"), 200)!!
            assertThat(far["timeZone"].asString()).isEqualTo("Pacific/Kiritimati")
            assertThat(far["configured"].asBoolean()).isTrue()
            assertThat(get("/api/lesen/progress/dashboard", token)["timeZone"].asString()).isEqualTo("Pacific/Kiritimati")
            val moved = rows()
            assertThat(moved.sumOf { it[2].toInt() }).isEqualTo(incremental.sumOf { it[2].toInt() })
            utc(token)
            assertThat(rows()).isEqualTo(incremental)
        }
    }

    @Test
    fun `goals are validated and the daily minutes live in the profile`() {
        val token = tomas()
        ensureProfile(token)
        val goals = send("PUT", "/api/lesen/progress/goals", token, mapOf("dailyMinutes" to 20, "weeklyDays" to 5, "weeklySummary" to true), 200)!!
        assertThat(goals["dailyMinutes"].asInt()).isEqualTo(20)
        assertThat(goals["weeklyDays"].asInt()).isEqualTo(5)
        assertThat(goals["weeklySummary"].asBoolean()).isTrue()
        assertThat(get("/api/lesen/learner/profile", token)["dailyMinutes"].asInt()).isEqualTo(20)
        assertThat(get("/api/lesen/progress/dashboard", token)["streak"]["target"].asInt()).isEqualTo(5)
        send("PUT", "/api/lesen/progress/goals", token, mapOf("timeZone" to "Mars/Olympus"), 400)
        send("PUT", "/api/lesen/progress/goals", token, mapOf("weeklyDays" to 0), 400)
        send("PUT", "/api/lesen/progress/goals", token, mapOf("dailyMinutes" to 1), 400)
        send("PUT", "/api/lesen/progress/goals", token, mapOf("weeklyDays" to 3, "weeklySummary" to false, "dailyMinutes" to 10), 200)
    }

    @Test
    fun `progress is personal, refused without notebook rights and 401 without a token`() {
        send("GET", "/api/lesen/progress/dashboard", editor(), null, 403)
        send("PUT", "/api/lesen/progress/goals", editor(), mapOf("weeklyDays" to 2), 403)
        send("GET", "/api/lesen/lists", editor(), null, 403)
        client
            .get()
            .uri("/api/lesen/progress/dashboard")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `reading lists belong to their owner`() {
        val token = lena()
        ensureProfile(token)
        val name = "Para el viaje ${UUID.randomUUID().toString().take(8)}"
        val list = send("POST", "/api/lesen/lists", token, mapOf("name" to "  $name "), 201)!!
        val id = list["id"].asString()
        assertThat(list["name"].asString()).isEqualTo(name)
        send("POST", "/api/lesen/lists", token, mapOf("name" to name.uppercase()), 409)
        send("POST", "/api/lesen/lists", token, mapOf("name" to " "), 400)

        send("PUT", "/api/lesen/lists/$id/readings/der-fuchs-im-garten", token, null, 204)
        send("PUT", "/api/lesen/lists/$id/readings/der-fuchs-im-garten", token, null, 204)
        send("PUT", "/api/lesen/lists/$id/readings/der-alte-leuchtturm", token, null, 204)
        send("PUT", "/api/lesen/lists/$id/readings/no-such-reading", token, null, 404)
        val full = get("/api/lesen/lists/$id", token)
        assertThat(full["readings"].values().map { it["slug"].asString() }).containsExactly("der-alte-leuchtturm", "der-fuchs-im-garten")
        val summary = get("/api/lesen/lists?reading=der-fuchs-im-garten", token).values().first { it["id"].asString() == id }
        assertThat(summary["count"].asInt()).isEqualTo(2)
        assertThat(summary["contains"].asBoolean()).isTrue()
        assertThat(get("/api/lesen/lists", token).values().first { it["id"].asString() == id }["contains"].isNull).isTrue()

        // otra persona no la ve ni la cambia
        val other = tomas()
        assertThat(get("/api/lesen/lists", other).values().none { it["id"].asString() == id }).isTrue()
        send("GET", "/api/lesen/lists/$id", other, null, 404)
        send("PUT", "/api/lesen/lists/$id/readings/ein-sonntag-bei-oma", other, null, 404)
        send("PATCH", "/api/lesen/lists/$id", other, mapOf("name" to "mía"), 404)
        send("DELETE", "/api/lesen/lists/$id", other, null, 404)

        val renamed = send("PATCH", "/api/lesen/lists/$id", token, mapOf("name" to "$name (2)"), 200)!!
        assertThat(renamed["name"].asString()).isEqualTo("$name (2)")
        send("DELETE", "/api/lesen/lists/$id/readings/der-fuchs-im-garten", token, null, 204)
        assertThat(get("/api/lesen/lists/$id", token)["readings"].size()).isEqualTo(1)
        send("DELETE", "/api/lesen/lists/$id", token, null, 204)
        send("GET", "/api/lesen/lists/$id", token, null, 404)
    }

    @Test
    fun `a weekly summary of a future week is empty, never negative`() {
        val token = lena()
        ensureProfile(token)
        val future = get("/api/lesen/progress/weekly?week=${LocalDate.now().plusWeeks(3)}", token)
        assertThat(future["activeDays"].asInt()).isZero()
        assertThat(future["met"].asBoolean()).isFalse()
        assertThat(future["days"].values().all { it["minutes"].asInt() == 0 }).isTrue()
    }
}
