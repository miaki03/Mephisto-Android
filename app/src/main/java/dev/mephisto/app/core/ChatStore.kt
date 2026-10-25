package dev.mephisto.app.core

/**
 * 会话消息的**内存缓存**。
 *
 * 为什么要有它：之前每次点进会话都发一次 `GET /messages`，而 SSE 又在重连时把历史事件
 * 一条条推回来、每条都触发一次全量重拉——真机上表现为"进会话要等、还卡"。
 *
 * 现在的规则：
 * 1. 有缓存就**立刻显示**，不转圈；
 * 2. 后台只在缓存缺失或超过 `staleMillis` 时补一次；
 * 3. SSE 推来的消息**合并进缓存**（按 id 去重、按 seq 排序），不再全量重拉。
 *
 * 进程被杀就没了——这是刻意的：本地持久化（Room）等真正需要离线看的时候再加。
 */
class ChatStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleMillis: Long = DEFAULT_STALE_MILLIS,
) {
    private val entries = HashMap<String, Entry>()
    private var versionCounter = 0

    /** 每次内容变化 +1，供 Compose 当 key 用。 */
    val version: Int get() = versionCounter

    fun messages(sessionId: String): List<Message> = entries[sessionId]?.messages ?: emptyList()

    fun has(sessionId: String): Boolean = entries.containsKey(sessionId)

    fun stale(sessionId: String): Boolean {
        val entry = entries[sessionId] ?: return true
        return clock() - entry.fetchedAtMillis > staleMillis
    }

    /** 用一次完整响应替换缓存（打开会话时的那次拉取）。 */
    fun put(sessionId: String, messages: List<Message>): Boolean {
        val previous = entries[sessionId]?.messages
        if (previous == messages) return false
        entries[sessionId] = Entry(dedupe(messages), clock())
        versionCounter += 1
        return true
    }

    /**
     * 合并一条来自 SSE 的消息。重复的（同 id）直接覆盖，不会出现两条。
     * 返回是否改动了缓存。
     */
    fun merge(sessionId: String, message: Message): Boolean {
        val current = entries[sessionId]?.messages ?: emptyList()
        val existing = current.firstOrNull { it.id == message.id }
        if (existing == message) return false
        val merged = if (existing == null) current + message else current.map { if (it.id == message.id) message else it }
        entries[sessionId] = Entry(dedupe(merged), entries[sessionId]?.fetchedAtMillis ?: clock())
        versionCounter += 1
        return true
    }

    /** 标记刚拉过一次：避免后台请求还没回来时被重复触发。 */
    fun touch(sessionId: String) {
        entries[sessionId] = Entry(entries[sessionId]?.messages ?: emptyList(), clock())
    }

    fun evict(sessionId: String) {
        if (entries.remove(sessionId) != null) versionCounter += 1
    }

    fun clear() {
        entries.clear()
        versionCounter += 1
    }

    fun cachedSessionIds(): Set<String> = entries.keys.toSet()

    private fun dedupe(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val seen = HashSet<String>(messages.size)
        val unique = ArrayList<Message>(messages.size)
        for (message in messages) {
            if (seen.add(message.id)) unique.add(message)
        }
        return unique.sortedBy { it.seq }
    }

    private data class Entry(val messages: List<Message>, val fetchedAtMillis: Long)

    companion object {
        /** 30 秒内不重复拉：够短，不至于看到过期数据；够长，来回切会话不会打网络。 */
        const val DEFAULT_STALE_MILLIS = 30_000L
    }
}
