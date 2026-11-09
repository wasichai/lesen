package lesen.audio

import lesen.LesenApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

class AudioApiTest : LesenApiTest() {
    @Test
    fun `a demo reading has its audio with one timing per word and derived ranges`() {
        val token = lena()
        val audio = get("/api/lesen/readings/der-alte-leuchtturm/versions/1/audio", token)
        val asset = audio["assets"][0]
        assertThat(asset["voice"]["language"].asString()).isEqualTo("de")
        assertThat(asset["url"].asString()).isEqualTo("/api/lesen/public/audio/der-alte-leuchtturm.mp3")
        val content = get("/api/lesen/readings/der-alte-leuchtturm/versions/1/content", token)
        val words =
            content["paragraphs"].values().flatMap { p -> p["sentences"].values().flatMap { s -> s["tokens"].values() } }.filter {
                it["kind"].asString() ==
                    "WORD"
            }
        assertThat(asset["timings"].size()).isEqualTo(words.size)
        val starts = asset["timings"].values().map { it[1].asInt() }
        assertThat(starts).isSorted
        assertThat(asset["sentences"][0]["id"].asString()).isEqualTo("p1s1")
        assertThat(asset["paragraphs"].size()).isEqualTo(content["paragraphs"].size())
        assertThat(asset["durationMs"].asInt()).isGreaterThan(starts.last())
    }

    @Test
    fun `audio metadata needs a token and is cached as immutable`() {
        client
            .get()
            .uri("/api/lesen/readings/der-alte-leuchtturm/versions/1/audio")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/api/lesen/readings/der-alte-leuchtturm/versions/1/audio")
            .header("Authorization", lena())
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueMatches(HttpHeaders.CACHE_CONTROL, ".*immutable.*")
        client
            .get()
            .uri("/api/lesen/readings/der-alte-leuchtturm/versions/99/audio")
            .header("Authorization", lena())
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `the file is public and answers byte ranges so the player can seek`() {
        client
            .get()
            .uri("/api/lesen/public/audio/der-alte-leuchtturm.mp3")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType("audio/mpeg")
        val partial =
            client
                .get()
                .uri("/api/lesen/public/audio/der-alte-leuchtturm.mp3")
                .header(HttpHeaders.RANGE, "bytes=1000-1999")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.PARTIAL_CONTENT)
                .expectBody(ByteArray::class.java)
                .returnResult()
        assertThat(partial.responseBody).hasSize(1000)
        assertThat(partial.responseHeaders.getFirst(HttpHeaders.CONTENT_RANGE)).startsWith("bytes 1000-1999/")
        client
            .get()
            .uri("/api/lesen/public/audio/missing.mp3")
            .exchange()
            .expectStatus()
            .isNotFound
        client
            .get()
            .uri("/api/lesen/public/audio/notes.txt")
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
