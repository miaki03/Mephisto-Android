package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCodeTest {
    @Test
    fun `same notice id always yields the same request code`() {
        val id = "01M5S7EQHFQA10MR0S5SPK779J"
        assertEquals(ReminderPlan.requestCode(id), ReminderPlan.requestCode(id))
    }

    @Test
    fun `request codes are non negative`() {
        val ids = listOf("a", "b", "01M5S7EQHFQA10MR0S5SPK779J", "zzzz", "0")
        for (id in ids) {
            assertTrue("负数会让日志难读", ReminderPlan.requestCode(id) >= 0)
        }
    }

    @Test
    fun `different ids practically never collide`() {
        val codes = HashSet<Int>()
        for (index in 0 until 2000) {
            val id = String.format("01M5S7EQHFQA10MR0S5SPK%04d", index)
            codes.add(ReminderPlan.requestCode(id))
        }
        assertEquals("2000 个 ULID 不该撞", 2000, codes.size)
    }

    @Test
    fun `two known ids differ`() {
        assertNotEquals(
            ReminderPlan.requestCode("01M5S7EQHFQA10MR0S5SPK779J"),
            ReminderPlan.requestCode("01M5S7EQHFQA10MR0S5SPK779K"),
        )
    }
}

class ParseMillisTest {
    @Test
    fun `parses offset date time`() {
        val expected = java.time.Instant.parse("2026-09-20T14:13:20Z").toEpochMilli()
        assertEquals(expected, ReminderPlan.parseMillis("2026-09-20T14:13:20+00:00"))
    }

    @Test
    fun `parses with positive offset`() {
        val plus8 = ReminderPlan.parseMillis("2026-09-20T22:13:20+08:00")
        assertEquals(java.time.Instant.parse("2026-09-20T14:13:20Z").toEpochMilli(), plus8)
    }

    @Test
    fun `garbage yields null instead of throwing`() {
        assertEquals(null, ReminderPlan.parseMillis("明天"))
        assertEquals(null, ReminderPlan.parseMillis(""))
    }
}

class ReminderPlanTest {
    private val now = 1_790_000_000_000L   // 2026-09-20T14:13:20Z

    private fun notice(
        id: String,
        dueOffsetSeconds: Long,
        state: String = "pending",
    ): Notice {
        val millis = now + dueOffsetSeconds * 1000
        val iso = java.time.Instant.ofEpochMilli(millis).toString()
        return Notice(id = id, kind = "reminder", title = "交表$id", body = null, dueUtc = iso, state = state)
    }

    @Test
    fun `future notice becomes a scheduled alarm`() {
        val plans = ReminderPlan.plan(listOf(notice("n1", 600)), now, exactAllowed = true)
        assertEquals(1, plans.size)
        assertEquals(PlanKind.FUTURE, plans[0].kind)
        assertEquals(now + 600_000, plans[0].triggerAtMillis)
        assertTrue(plans[0].exact)
        assertEquals(ReminderPlan.requestCode("n1"), plans[0].requestCode)
    }

    @Test
    fun `overdue within grace fires immediately`() {
        val plans = ReminderPlan.plan(listOf(notice("n1", -600)), now, exactAllowed = true)
        assertEquals(PlanKind.OVERDUE, plans[0].kind)
        assertEquals(now, plans[0].triggerAtMillis)
    }

    @Test
    fun `way overdue is skipped so the server summary owns it`() {
        val plans = ReminderPlan.plan(listOf(notice("n1", -4 * 3600)), now, exactAllowed = true)
        assertEquals(PlanKind.SKIP, plans[0].kind)
    }

    @Test
    fun `acknowledged notice is never scheduled`() {
        val plans = ReminderPlan.plan(listOf(notice("n1", 600, state = "acked")), now, exactAllowed = true)
        assertEquals(0, plans.size)
    }

    @Test
    fun `duplicate ids are planned once`() {
        val plans = ReminderPlan.plan(listOf(notice("n1", 600), notice("n1", 600)), now, exactAllowed = true)
        assertEquals(1, plans.size)
    }

    @Test
    fun `unknown due time is skipped not crashed`() {
        val broken = Notice(id = "n9", kind = "reminder", title = "坏的", body = null, dueUtc = "not-a-date", state = "pending")
        val plans = ReminderPlan.plan(listOf(broken), now, exactAllowed = true)
        assertEquals(PlanKind.SKIP, plans[0].kind)
    }

    @Test
    fun `exact permission off is propagated so caller can fall back`() {
        val plans = ReminderPlan.plan(listOf(notice("n1", 600)), now, exactAllowed = false)
        assertEquals(false, plans[0].exact)
    }

    @Test
    fun `schedulable sorts by trigger time`() {
        val plans = ReminderPlan.plan(
            listOf(notice("late", 3600), notice("soon", 60), notice("mid", 600)),
            now,
            exactAllowed = true,
        )
        assertEquals(listOf("soon", "mid", "late"), ReminderPlan.schedulable(plans).map { it.noticeId })
    }

    @Test
    fun `overdue helper picks only overdue ones`() {
        val plans = ReminderPlan.plan(
            listOf(notice("future", 600), notice("past", -60)),
            now,
            exactAllowed = true,
        )
        assertEquals(listOf("past"), ReminderPlan.overdue(plans).map { it.noticeId })
    }

    @Test
    fun `planning twice yields identical request codes`() {
        val first = ReminderPlan.plan(listOf(notice("n1", 600)), now, exactAllowed = true)
        val second = ReminderPlan.plan(listOf(notice("n1", 600)), now + 1000, exactAllowed = true)
        assertEquals("重排必须能取消掉旧闹钟", first[0].requestCode, second[0].requestCode)
    }
}

class KeepAliveTest {
    @Test
    fun `off level starts nothing`() {
        val decision = KeepAlive.shouldStart(KeepAliveLevel.OFF, notificationsAllowed = true, batteryOptimized = false)
        assertEquals(false, decision.startService)
    }

    @Test
    fun `persistent starts with a clean reason`() {
        val decision = KeepAlive.shouldStart(KeepAliveLevel.PERSISTENT, notificationsAllowed = true, batteryOptimized = false)
        assertEquals(true, decision.startService)
        assertTrue(decision.reason.contains("已启动"))
    }

    @Test
    fun `missing notification permission is called out`() {
        val decision = KeepAlive.shouldStart(KeepAliveLevel.PERSISTENT, notificationsAllowed = false, batteryOptimized = false)
        assertEquals(true, decision.startService)
        assertTrue(decision.reason.contains("通知权限"))
    }

    @Test
    fun `aggressive level mentions battery optimization`() {
        val decision = KeepAlive.shouldStart(KeepAliveLevel.AGGRESSIVE, notificationsAllowed = true, batteryOptimized = true)
        assertEquals(true, decision.startService)
        assertTrue(decision.reason.contains("电池优化"))
    }

    @Test
    fun `reschedule actions are recognised`() {
        assertTrue(KeepAlive.shouldReschedule("android.intent.action.BOOT_COMPLETED"))
        assertTrue(KeepAlive.shouldReschedule("android.intent.action.TIMEZONE_CHANGED"))
        assertTrue(KeepAlive.shouldReschedule("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(KeepAlive.shouldReschedule("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
    }

    @Test
    fun `unrelated actions are ignored`() {
        assertEquals(false, KeepAlive.shouldReschedule("android.intent.action.SCREEN_OFF"))
        assertEquals(false, KeepAlive.shouldReschedule(null))
    }
}
