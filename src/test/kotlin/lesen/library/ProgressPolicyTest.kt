package lesen.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ProgressPolicyTest {
    private val t0 = Instant.parse("2026-11-01T10:00:00Z")
    private val t1 = Instant.parse("2026-11-01T10:05:00Z")

    @Test
    fun `the first report starts the reading`() {
        val state = ProgressPolicy.merge(null, ProgressReport(1, "p2s1", 40, 60), t0)
        assertThat(state).isEqualTo(ProgressState(1, "p2s1", 40, 60, t0, t0, null))
    }

    @Test
    fun `position follows the reader while percent and seconds never go down`() {
        val first = ProgressPolicy.merge(null, ProgressReport(1, "p4s1", 80, 300), t0)
        val back = ProgressPolicy.merge(first, ProgressReport(1, "p1s2", 20, 120), t1)
        assertThat(back.position).isEqualTo("p1s2")
        assertThat(back.percent).isEqualTo(80)
        assertThat(back.secondsRead).isEqualTo(300)
        assertThat(back.startedAt).isEqualTo(t0)
    }

    @Test
    fun `the same report twice leaves the same state`() {
        val report = ProgressReport(1, "p2s1", 40, 60)
        val once = ProgressPolicy.merge(null, report, t0)
        assertThat(ProgressPolicy.merge(once, report, t0)).isEqualTo(once)
    }

    @Test
    fun `another version restarts its percent`() {
        val old = ProgressPolicy.merge(null, ProgressReport(1, "p5s1", 90, 400), t0)
        val next = ProgressPolicy.merge(old, ProgressReport(2, "p1s1", 5, 410), t1)
        assertThat(next.version).isEqualTo(2)
        assertThat(next.percent).isEqualTo(5)
    }

    @Test
    fun `completing is idempotent and sets 100 percent`() {
        val done = ProgressPolicy.complete(ProgressPolicy.merge(null, ProgressReport(1, "p2s1", 40, 60), t0), 1, 90, t0)
        assertThat(done.percent).isEqualTo(100)
        assertThat(done.completedAt).isEqualTo(t0)
        val again = ProgressPolicy.complete(done, 1, 95, t1)
        assertThat(again.completedAt).isEqualTo(t0)
        assertThat(ProgressPolicy.merge(again, ProgressReport(1, "p1s1", 10, 100), t1).completedAt).isEqualTo(t0)
    }

    @Test
    fun `seconds are capped`() {
        assertThat(ProgressPolicy.merge(null, ProgressReport(1, null, 0, Int.MAX_VALUE), t0).secondsRead).isEqualTo(ProgressPolicy.MAX_SECONDS)
    }
}
