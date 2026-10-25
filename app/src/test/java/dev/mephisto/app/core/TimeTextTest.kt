package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeTextTest {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** 用本地时区构造一个 ISO 串，避免测试跟着机器时区变。 */
    private fun iso(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toOffsetDateTime().toString()

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `just now under a minute`() {
        val now = millis(2026, 10, 25, 12, 0)
        // 59 秒：验证的是"不到一分钟"这条边界，不是正好 60 秒
        val fiftyNine = now - 59_000
        assertEquals("刚刚", TimeText.relative(now, java.time.Instant.ofEpochMilli(fiftyNine).toString()))
    }

    @Test
    fun `minutes ago under an hour`() {
        val now = millis(2026, 10, 25, 12, 0)
        assertEquals("5 分钟前", TimeText.relative(now, iso(2026, 10, 25, 11, 55)))
        assertEquals("59 分钟前", TimeText.relative(now, iso(2026, 10, 25, 11, 1)))
    }

    @Test
    fun `same day shows clock`() {
        val now = millis(2026, 10, 25, 20, 0)
        assertEquals("09:30", TimeText.relative(now, iso(2026, 10, 25, 9, 30)))
    }

    @Test
    fun `yesterday is spelled out`() {
        val now = millis(2026, 10, 25, 9, 0)
        assertEquals("昨天", TimeText.relative(now, iso(2026, 10, 24, 21, 0)))
    }

    @Test
    fun `same year shows month and day`() {
        val now = millis(2026, 10, 25, 9, 0)
        assertEquals("3 月 8 日", TimeText.relative(now, iso(2026, 3, 8, 21, 0)))
    }

    @Test
    fun `previous year shows full date`() {
        val now = millis(2026, 10, 25, 9, 0)
        assertEquals("2025 年 12 月 31 日", TimeText.relative(now, iso(2025, 12, 31, 23, 0)))
    }

    @Test
    fun `unparsable timestamps yield null instead of garbage`() {
        assertNull(TimeText.relative(System.currentTimeMillis(), null))
        assertNull(TimeText.relative(System.currentTimeMillis(), ""))
        assertNull(TimeText.relative(System.currentTimeMillis(), "昨天下午"))
    }

    @Test
    fun `future timestamps do not show negative minutes`() {
        val now = millis(2026, 10, 25, 12, 0)
        val label = TimeText.relative(now, iso(2026, 10, 25, 12, 5))
        assertEquals("12:05", label)
    }

    @Test
    fun `separator appears for the first message`() {
        assertTrue(TimeText.showSeparator(null, iso(2026, 10, 25, 9, 0)))
    }

    @Test
    fun `separator appears after a long gap`() {
        val a = iso(2026, 10, 25, 9, 0)
        val b = iso(2026, 10, 25, 9, 11)
        assertTrue(TimeText.showSeparator(a, b))
    }

    @Test
    fun `no separator for a short gap`() {
        val a = iso(2026, 10, 25, 9, 0)
        val b = iso(2026, 10, 25, 9, 5)
        assertFalse(TimeText.showSeparator(a, b))
    }

    @Test
    fun `separator label includes a clock time`() {
        // separatorLabel 以"现在"为今天，用真实当前时间构造才不会跨年
        val nowIso = java.time.OffsetDateTime.now().toString()
        val label = TimeText.separatorLabel(nowIso)
        assertTrue("应该含 HH:mm，实际：$label", label.contains(":"))
        assertTrue("应该以「今天」开头，实际：$label", label.startsWith("今天"))
    }

    @Test
    fun `clock is zero padded`() {
        assertEquals("09:05", TimeText.clock(millis(2026, 10, 25, 9, 5)))
        assertEquals("23:59", TimeText.clock(millis(2026, 10, 25, 23, 59)))
    }

    @Test
    fun `days ago counts calendar days`() {
        val now = millis(2026, 10, 25, 0, 30)
        assertEquals(1L, TimeText.daysAgo(now, iso(2026, 10, 24, 23, 30)))
    }
}
