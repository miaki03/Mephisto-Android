package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三段后悔的第①段：**3 秒内撤销一个请求都不发**。
 *
 * 这些用例把"后悔期"钉成状态机行为，而不是界面上的动画——
 * 动画可以骗人，状态机不行。
 */
class OutboxTest {
    private var now = 1_000L
    private var counter = 0

    private fun newId(): String {
        counter += 1
        return "c_$counter"
    }

    private fun outbox(graceMillis: Long = 3_000, maxDepth: Int = 5) =
        Outbox(clock = { now }, graceMillis = graceMillis, maxDepth = maxDepth)

    @Test
    fun `new message waits in grace and is not due immediately`() {
        val box = outbox()
        val item = box.enqueue("s1", "你好", ::newId)
        assertEquals(OutboxState.GRACE, item.state)
        assertEquals("刚入箱不该立刻发送", 0, box.dueToSend().size)
        now += 3_000
        val due = box.dueToSend()
        assertEquals(1, due.size)
        assertEquals(OutboxState.SENDING, due[0].state)
        assertEquals("c_1", due[0].clientMsgId)
    }

    @Test
    fun `undo inside grace drops locally without any job`() {
        val box = outbox()
        val item = box.enqueue("s1", "撤销我", ::newId)
        assertEquals(UndoPlan.Drop, box.undo(item.clientMsgId))
        assertEquals("后悔期撤销等于没发生过", 0, box.items().size)
        assertNull(box.get(item.clientMsgId))
    }

    @Test
    fun `undo after sending asks the server to cancel the job`() {
        val box = outbox()
        val item = box.enqueue("s1", "已提交", ::newId)
        now += 3_000
        box.dueToSend()
        box.markQueued(item.clientMsgId, "job-1")
        val plan = box.undo(item.clientMsgId)
        assertTrue(plan is UndoPlan.CancelJob)
        assertEquals("job-1", (plan as UndoPlan.CancelJob).jobId)
    }

    @Test
    fun `undo while running asks to interrupt`() {
        val box = outbox()
        val item = box.enqueue("s1", "跑到一半", ::newId)
        now += 3_000
        box.dueToSend()
        box.markQueued(item.clientMsgId, "job-2")
        box.markRunning(item.clientMsgId)
        assertEquals("job-2", (box.undo(item.clientMsgId) as UndoPlan.CancelJob).jobId)
    }

    @Test
    fun `undo on finished message is too late`() {
        val box = outbox()
        val item = box.enqueue("s1", "完成了", ::newId)
        now += 3_000
        box.dueToSend()
        box.markDone(item.clientMsgId)
        val plan = box.undo(item.clientMsgId)
        assertTrue(plan is UndoPlan.TooLate)
        assertEquals(OutboxState.DONE, (plan as UndoPlan.TooLate).state)
    }

    @Test
    fun `undo of unknown id is not found`() {
        assertEquals(UndoPlan.NotFound, outbox().undo("nope"))
    }

    @Test
    fun `retry reuses the same client msg id`() {
        val box = outbox()
        val item = box.enqueue("s1", "飞行模式", ::newId)
        now += 3_000
        box.dueToSend()
        box.markFailed(item.clientMsgId, "网络不通")
        val retried = box.retry(item.clientMsgId)
        assertEquals("换 id 就会在服务端变成第二条消息", item.clientMsgId, retried?.clientMsgId)
        assertEquals(2, retried?.attempts)
        assertEquals(OutboxState.SENDING, retried?.state)
    }

    @Test
    fun `edit inside grace keeps the id`() {
        val box = outbox()
        val item = box.enqueue("s1", "错字", ::newId)
        assertEquals(UndoPlan.Drop, box.edit(item.clientMsgId, "改好了", ::newId))
        assertEquals("改好了", box.get(item.clientMsgId)?.text)
        assertEquals("c_1", box.get(item.clientMsgId)?.clientMsgId)
    }

    @Test
    fun `edit of a queued message creates a new id and cancels the old job`() {
        val box = outbox()
        val item = box.enqueue("s1", "写错了", ::newId)
        now += 3_000
        box.dueToSend()
        box.markQueued(item.clientMsgId, "job-3")

        val plan = box.edit(item.clientMsgId, "改成这样", ::newId)
        assertEquals("job-3", (plan as UndoPlan.CancelJob).jobId)
        assertNull("旧条目必须消失", box.get(item.clientMsgId))
        val replacement = box.items().single()
        assertEquals("改成这样", replacement.text)
        assertEquals(OutboxState.SENDING, replacement.state)
    }

    @Test
    fun `depth is capped per session`() {
        val box = outbox(maxDepth = 2)
        box.enqueue("s1", "一", ::newId)
        box.enqueue("s1", "二", ::newId)
        assertThrows(QueueFullException::class.java) { box.enqueue("s1", "三", ::newId) }
        box.enqueue("s2", "另一个会话不受影响", ::newId)
    }

    @Test
    fun `terminal items do not count against depth`() {
        val box = outbox(maxDepth = 1)
        val first = box.enqueue("s1", "一", ::newId)
        box.markDone(first.clientMsgId)
        box.enqueue("s1", "二", ::newId)
        assertEquals(1, box.pending("s1").size)
    }

    @Test
    fun `grace countdown is visible to the ui`() {
        val box = outbox(graceMillis = 3_000)
        val item = box.enqueue("s1", "倒计时", ::newId)
        assertEquals(3_000L, item.graceLeft(now))
        now += 1_200
        assertEquals(1_800L, box.get(item.clientMsgId)!!.graceLeft(now))
        now += 5_000
        assertEquals(0L, box.get(item.clientMsgId)!!.graceLeft(now))
    }

    @Test
    fun `reconcile aligns local state with server events`() {
        val box = outbox()
        val item = box.enqueue("s1", "重连后补齐", ::newId)
        box.reconcile(item.clientMsgId, OutboxState.DONE)
        assertEquals(OutboxState.DONE, box.get(item.clientMsgId)?.state)
        assertFalse(box.pending().any { it.clientMsgId == item.clientMsgId })
    }
}
