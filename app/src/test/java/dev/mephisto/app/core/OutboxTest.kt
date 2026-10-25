package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发件箱：**没有后悔期**——入箱即发送。"反悔"只剩两段：排队中取消、运行中打断。
 */
class OutboxTest {
    private var counter = 0

    private fun newId(): String {
        counter += 1
        return "c_$counter"
    }

    private fun outbox(maxDepth: Int = 5) = Outbox(maxDepth = maxDepth)

    @Test
    fun `enqueue starts sending immediately`() {
        val box = outbox()
        val item = box.enqueue("s1", "你好", ::newId)
        assertEquals(OutboxState.SENDING, item.state)
        assertEquals(1, item.attempts)
        assertEquals(1, box.pending("s1").size)
    }

    @Test
    fun `queue then run then done`() {
        val box = outbox()
        val item = box.enqueue("s1", "你好", ::newId)
        box.markQueued(item.clientMsgId, "job-1")
        assertEquals(OutboxState.QUEUED, box.get(item.clientMsgId)?.state)
        box.markRunning(item.clientMsgId)
        assertEquals("执行中", box.get(item.clientMsgId)?.badge())
        box.markDone(item.clientMsgId)
        assertEquals(OutboxState.DONE, box.get(item.clientMsgId)?.state)
        assertEquals("已送达就不再占发件箱", 0, box.pending("s1").size)
    }

    @Test
    fun `cancel while queued asks the server to drop the job`() {
        val box = outbox()
        val item = box.enqueue("s1", "已提交", ::newId)
        box.markQueued(item.clientMsgId, "job-1")
        val plan = box.cancel(item.clientMsgId)
        assertTrue(plan is UndoPlan.CancelJob)
        assertEquals("job-1", (plan as UndoPlan.CancelJob).jobId)
    }

    @Test
    fun `cancel while running asks to interrupt`() {
        val box = outbox()
        val item = box.enqueue("s1", "跑到一半", ::newId)
        box.markQueued(item.clientMsgId, "job-2")
        box.markRunning(item.clientMsgId)
        assertEquals("job-2", (box.cancel(item.clientMsgId) as UndoPlan.CancelJob).jobId)
    }

    @Test
    fun `cancel before the server answered has no job id yet`() {
        val box = outbox()
        val item = box.enqueue("s1", "刚发出", ::newId)
        val plan = box.cancel(item.clientMsgId)
        assertTrue(plan is UndoPlan.CancelJob)
        assertNull("还没拿到 job_id，只能标记本地取消", (plan as UndoPlan.CancelJob).jobId)
    }

    @Test
    fun `cancel on finished message is too late`() {
        val box = outbox()
        val item = box.enqueue("s1", "完成了", ::newId)
        box.markDone(item.clientMsgId)
        val plan = box.cancel(item.clientMsgId)
        assertTrue(plan is UndoPlan.TooLate)
        assertEquals(OutboxState.DONE, (plan as UndoPlan.TooLate).state)
    }

    @Test
    fun `cancel of unknown id is not found`() {
        assertEquals(UndoPlan.NotFound, outbox().cancel("nope"))
    }

    @Test
    fun `retry reuses the same client msg id`() {
        val box = outbox()
        val item = box.enqueue("s1", "断网了", ::newId)
        box.markFailed(item.clientMsgId, "网络不通")
        val retried = box.retry(item.clientMsgId)
        assertEquals("换 id 就会在服务端变成第二条消息", item.clientMsgId, retried?.clientMsgId)
        assertEquals(2, retried?.attempts)
        assertEquals(OutboxState.SENDING, retried?.state)
    }

    @Test
    fun `retry is refused for done and cancelled`() {
        val box = outbox()
        val done = box.enqueue("s1", "a", ::newId)
        box.markDone(done.clientMsgId)
        assertNull(box.retry(done.clientMsgId))

        val cancelled = box.enqueue("s1", "b", ::newId)
        box.markCancelled(cancelled.clientMsgId)
        assertNull(box.retry(cancelled.clientMsgId))
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
    fun `each enqueue gets a fresh id`() {
        val box = outbox()
        val first = box.enqueue("s1", "a", ::newId)
        val second = box.enqueue("s1", "b", ::newId)
        assertNotEquals(first.clientMsgId, second.clientMsgId)
    }

    @Test
    fun `reconcile aligns local state with server events`() {
        val box = outbox()
        val item = box.enqueue("s1", "重连后补齐", ::newId)
        box.reconcile(item.clientMsgId, OutboxState.DONE)
        assertEquals(OutboxState.DONE, box.get(item.clientMsgId)?.state)
        assertEquals(0, box.pending().size)
    }

    @Test
    fun `badges describe every state`() {
        assertEquals("发送中", Outgoing("c", "s", "t", 0).badge())
        assertEquals("已排队", Outgoing("c", "s", "t", 0, OutboxState.QUEUED).badge())
        assertEquals("执行中", Outgoing("c", "s", "t", 0, OutboxState.RUNNING).badge())
        assertEquals("已送达", Outgoing("c", "s", "t", 0, OutboxState.DONE).badge())
        assertEquals("失败", Outgoing("c", "s", "t", 0, OutboxState.FAILED).badge())
        assertEquals("已取消", Outgoing("c", "s", "t", 0, OutboxState.CANCELLED).badge())
    }
}
