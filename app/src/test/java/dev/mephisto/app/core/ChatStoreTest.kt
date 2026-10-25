package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun msg(id: String, seq: Int, text: String = "t$id", direction: String = "in") =
    Message(id = id, seq = seq, direction = direction, text = text, createdAt = "t", clientMsgId = null, sessionId = "s1")

class ChatStoreTest {
    private var now = 1_000_000L
    private val store = ChatStore(clock = { now })

    @Test
    fun `empty store reports stale and no data`() {
        assertFalse(store.has("s1"))
        assertTrue(store.stale("s1"))
        assertEquals(emptyList<Message>(), store.messages("s1"))
    }

    @Test
    fun `put stores messages sorted by seq`() {
        store.put("s1", listOf(msg("b", 2), msg("a", 1)))
        assertEquals(listOf("a", "b"), store.messages("s1").map { it.id })
        assertTrue(store.has("s1"))
        assertFalse(store.stale("s1"))
    }

    @Test
    fun `put drops duplicates by id`() {
        store.put("s1", listOf(msg("a", 1), msg("a", 1), msg("b", 2)))
        assertEquals(2, store.messages("s1").size)
    }

    @Test
    fun `merge appends a new message in order`() {
        store.put("s1", listOf(msg("a", 1)))
        assertTrue(store.merge("s1", msg("b", 2)))
        assertEquals(listOf("a", "b"), store.messages("s1").map { it.id })
    }

    @Test
    fun `merge replaces the same id instead of duplicating`() {
        store.put("s1", listOf(msg("a", 1, text = "旧")))
        assertTrue(store.merge("s1", msg("a", 1, text = "新")))
        assertEquals(1, store.messages("s1").size)
        assertEquals("新", store.messages("s1").first().text)
    }

    @Test
    fun `merging an identical message reports no change`() {
        store.put("s1", listOf(msg("a", 1)))
        assertFalse("重复事件不该触发重组", store.merge("s1", msg("a", 1)))
    }

    @Test
    fun `merge into unknown session creates the cache`() {
        assertTrue(store.merge("s2", msg("a", 1)))
        assertEquals(1, store.messages("s2").size)
    }

    @Test
    fun `merging from sse does not reset freshness`() {
        store.put("s1", listOf(msg("a", 1)))
        now += 10_000
        store.merge("s1", msg("b", 2))
        assertFalse("SSE 推来的消息说明连接是活的，不该触发全量重拉", store.stale("s1"))
    }

    @Test
    fun `stale after the window expires`() {
        store.put("s1", listOf(msg("a", 1)))
        now += ChatStore.DEFAULT_STALE_MILLIS + 1
        assertTrue(store.stale("s1"))
    }

    @Test
    fun `version bumps only on real changes`() {
        val start = store.version
        store.put("s1", listOf(msg("a", 1)))
        val afterPut = store.version
        assertTrue(afterPut > start)

        store.put("s1", listOf(msg("a", 1)))
        assertEquals("内容没变不该 bump", afterPut, store.version)

        store.merge("s1", msg("a", 1))
        assertEquals("重复事件不该 bump", afterPut, store.version)
    }

    @Test
    fun `evict forgets a session`() {
        store.put("s1", listOf(msg("a", 1)))
        store.evict("s1")
        assertFalse(store.has("s1"))
        assertTrue(store.stale("s1"))
    }

    @Test
    fun `out of order events still end up sorted`() {
        store.put("s1", listOf(msg("a", 1), msg("c", 3)))
        store.merge("s1", msg("b", 2))
        assertEquals(listOf(1, 2, 3), store.messages("s1").map { it.seq })
    }
}
