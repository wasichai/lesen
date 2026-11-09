package lesen.catalog

import lesen.learner.Difficulty
import lesen.learner.LearnerProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RecommenderTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val profile = LearnerProfile("es", "de", "A2", false, emptyList(), listOf("travel"), 10, Difficulty.BALANCED)

    private fun card(
        slug: String,
        level: String,
        topics: List<String> = listOf("history"),
        language: String = "de",
        completed: Boolean = false,
        published: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) = ReadingCard(
        slug,
        language,
        level,
        topics,
        false,
        1,
        slug,
        null,
        "",
        null,
        null,
        100,
        2,
        published,
        false,
        if (completed) CardProgress(100, 1, true, now) else null,
        0,
    )

    @Test
    fun `the level band follows the chosen difficulty`() {
        assertThat(Recommender.band(profile)).containsExactly("A2")
        assertThat(Recommender.band(profile.copy(difficulty = Difficulty.COMFORTABLE))).containsExactlyInAnyOrder("A1", "A2")
        assertThat(Recommender.band(profile.copy(difficulty = Difficulty.CHALLENGING))).containsExactlyInAnyOrder("A2", "B1")
        assertThat(Recommender.band(profile.copy(level = "A1", difficulty = Difficulty.COMFORTABLE))).containsExactly("A1")
    }

    @Test
    fun `level and topic rank higher, completed and other languages drop out, with reasons`() {
        val result =
            Recommender.recommend(
                profile,
                listOf(
                    card("far", "C1"),
                    card("level", "A2"),
                    card("both", "A2", listOf("travel/coasts")),
                    card("done", "A2", listOf("travel"), completed = true),
                    card("english", "A2", language = "en"),
                    card("fresh", "B1", published = now.minusSeconds(3600)),
                ),
                now,
            )
        assertThat(result.map { it.reading.slug }).containsExactly("both", "level", "fresh")
        assertThat(result.first().reasons).containsExactly("LEVEL", "TOPIC")
        assertThat(result.last().reasons).containsExactly("NEW")
    }

    @Test
    fun `measured comprehension moves the band and says so`() {
        assertThat(Recommender.band(profile, 1)).containsExactly("B1")
        assertThat(Recommender.band(profile, -1)).containsExactly("A1")
        assertThat(Recommender.band(profile.copy(level = "C2"), 1)).containsExactly("C2")
        val result = Recommender.recommend(profile, listOf(card("a2", "A2"), card("b1", "B1")), now, LearnerSignals(bandShift = 1))
        assertThat(result.first().reading.slug).isEqualTo("b1")
        assertThat(result.first().reasons).containsExactly("COMPREHENSION")
    }

    @Test
    fun `topics of finished readings and what others read this week count, with reasons`() {
        val result =
            Recommender.recommend(
                profile.copy(topics = emptyList()),
                listOf(
                    card("done", "A2", listOf("nature/forest"), completed = true),
                    card("plain", "A2", listOf("history")),
                    card("same-topic", "A2", listOf("nature")),
                    card("popular", "A2", listOf("history")),
                ),
                now,
                LearnerSignals(trending = mapOf("popular" to 3, "done" to 5)),
            )
        assertThat(result.map { it.reading.slug }).containsExactly("same-topic", "popular", "plain")
        assertThat(result[0].reasons).containsExactly("LEVEL", "HISTORY")
        assertThat(result[1].reasons).containsExactly("LEVEL", "TRENDING")
        assertThat(result[2].reasons).containsExactly("LEVEL")
    }
}
