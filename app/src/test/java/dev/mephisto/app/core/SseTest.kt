package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun `parses a full event`() {
        val parser = SseParser()
        assertNull(parser.feed("id: 42"))
        assertNull(parser.feed("event: message"))
        assertNull(parser.feed("data: {\"a\":1}"))
        val event = parser.feed("")
        assertEquals(42L, event?.id)
        assertEquals("message", event?.kind)
        assertEquals("{\"a\":1}", event?.data)
        assertEquals(42L, parser.lastEventId())
    }

    @Test
    fun `multiline data is joined with newlines`() {
        val parser = SseParser()
        parser.feed("data: line1")
        parser.feed("data: line2")
        val event = parser.feed("")
        assertEquals("line1\nline2", event?.data)
    }

    @Test
    fun `keepalive comment is ignored`() {
        val parser = SseParser()
        assertNull(parser.feed(": ping"))
        assertNull(parser.feed(""))
    }

    @Test
    fun `blank line without data yields nothing`() {
        val parser = SseParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed("event: message"))
        assertNull(parser.feed(""))
    }

    @Test
    fun `event without id keeps the previous id`() {
        val parser = SseParser()
        parser.feed("id: 7")
        parser.feed("data: x")
        parser.feed("")
        parser.feed("data: y")
        val second = parser.feed("")
        assertEquals("协议允许省略 id，表示沿用上一条", 7L, second?.id)
        assertEquals("y", second?.data)
    }

    @Test
    fun `defaults to message kind`() {
        val parser = SseParser()
        parser.feed("data: hi")
        assertEquals("message", parser.feed("")?.kind)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parser = SseParser()
        parser.feed("retry: 5000")
        parser.feed("data: hi")
        assertEquals("hi", parser.feed("")?.data)
    }

    @Test
    fun `field without colon is tolerated`() {
        val parser = SseParser()
        parser.feed("data")
        assertEquals("", parser.feed("")?.data)
    }

    @Test
    fun `reset clears the resume cursor`() {
        val parser = SseParser()
        parser.feed("id: 9")
        parser.feed("data: a")
        parser.feed("")
        parser.reset()
        assertNull(parser.lastEventId())
    }
}

class BackoffTest {
    @Test
    fun `doubles and caps at 30 seconds`() {
        assertEquals(500L, Backoff.delayMillis(0))
        assertEquals(1_000L, Backoff.delayMillis(1))
        assertEquals(2_000L, Backoff.delayMillis(2))
        assertEquals(4_000L, Backoff.delayMillis(3))
        assertEquals(30_000L, Backoff.delayMillis(10))
        assertEquals("必须封顶，否则退避会变成几小时", 30_000L, Backoff.delayMillis(99))
    }
}

class ProgressTrackerTest {
    private var now = 10_000L
    private val tracker = ProgressTracker(clock = { now })

    @Test
    fun `idle before anything happens`() {
        assertEquals("空闲", tracker.statusLine())
    }

    @Test
    fun `queued shows waiting seconds`() {
        tracker.start("job-1")
        now += 2_500
        assertEquals("已排队 2s", tracker.statusLine())
        assertTrue(tracker.working())
    }

    @Test
    fun `running shows elapsed seconds phase and tokens`() {
        tracker.start("job-1")
        now += 1_000
        tracker.apply("""{"state":"running","kind":"status","phase":"turn_start"}""")
        now += 11_000
        tracker.apply("""{"state":"running","phase":"step_end","usage":{"totalTokens":6059,"cacheReadTokens":5504}}""")

        val line = tracker.statusLine()
        assertTrue(line, line.startsWith("执行中 11s"))
        assertTrue(line, line.contains("一步结束"))
        assertTrue(line, line.contains("6059"))
        assertTrue(line, line.contains("缓存 5504"))
    }

    @Test
    fun `failed shows the reason`() {
        tracker.start("job-1")
        tracker.apply("""{"state":"failed","error":"模型不可用"}""")
        assertEquals("失败：模型不可用", tracker.statusLine())
        assertTrue(!tracker.working())
    }

    @Test
    fun `cancelled is distinguishable from failed`() {
        tracker.start("job-1")
        tracker.apply("""{"state":"cancelled"}""")
        assertEquals("已打断", tracker.statusLine())
    }

    @Test
    fun `wrapped job payload is unwrapped`() {
        tracker.start(null)
        tracker.apply("""{"job":{"state":"running","phase":"step_start"}}""")
        assertEquals("running", tracker.state)
    }

    @Test
    fun `broken json does not throw`() {
        assertTrue(!tracker.apply("not json"))
        assertEquals("空闲", tracker.statusLine())
    }

    @Test
    fun `finish returns to idle`() {
        tracker.start("job-1")
        tracker.finish()
        assertEquals("空闲", tracker.statusLine())
    }
}
