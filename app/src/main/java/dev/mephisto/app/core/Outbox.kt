package dev.mephisto.app.core

/**
 * 三段后悔的第①段：**还没发出去的**消息。
 *
 * 状态机只做本地判断，不碰网络——这样"3 秒内撤销不发请求"这条规则可以被单测钉死，
 * 而不是靠人肉点按去感觉。
 *
 * ```
 * GRACE --(到期)--> SENDING --(202)--> QUEUED --(SSE)--> RUNNING --> DONE
 *   |                   |                 |                 |
 *   +--undo--> 删掉      +--失败--> FAILED  +--undo--> 取消     +--undo--> 打断
 * ```
 */
enum class OutboxState { GRACE, SENDING, QUEUED, RUNNING, DONE, FAILED, CANCELLED }

val TERMINAL_STATES = setOf(OutboxState.DONE, OutboxState.FAILED, OutboxState.CANCELLED)

data class Outgoing(
    val clientMsgId: String,
    val sessionId: String,
    val text: String,
    val createdAtMillis: Long,
    val sendAtMillis: Long,
    val state: OutboxState = OutboxState.GRACE,
    val jobId: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
) {
    val terminal: Boolean get() = state in TERMINAL_STATES

    /** 后悔期还剩几毫秒（0 表示已经不再可撤销）。 */
    fun graceLeft(nowMillis: Long): Long = (sendAtMillis - nowMillis).coerceAtLeast(0)

    /** 界面上的角标。后悔期要显示倒计时，让人知道还剩多久能后悔。 */
    fun badge(nowMillis: Long): String = when (state) {
        OutboxState.GRACE -> String.format("撤销 %.1fs", graceLeft(nowMillis) / 1000.0)
        OutboxState.SENDING -> "发送中"
        OutboxState.QUEUED -> "已排队"
        OutboxState.RUNNING -> "执行中"
        OutboxState.DONE -> "已送达"
        OutboxState.FAILED -> "失败"
        OutboxState.CANCELLED -> "已取消"
    }
}

/** 撤销的三种结局。调用方据此决定「删本地」还是「打取消接口」。 */
sealed interface UndoPlan {
    /** ① 后悔期：本地删掉，一个请求都不发。 */
    data object Drop : UndoPlan

    /** ②③ 已提交：让服务端删行（排队中）或杀进程树（运行中）。 */
    data class CancelJob(val jobId: String?) : UndoPlan

    /** 已经结束，来不及了。 */
    data class TooLate(val state: OutboxState) : UndoPlan

    data object NotFound : UndoPlan
}

class QueueFullException(val depth: Int) : Exception("排队已满（$depth 条）")

/**
 * 发件箱。天然按会话隔离——不同会话的消息互不影响，同一会话才谈"排队"。
 */
class Outbox(
    private val clock: () -> Long,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private val items = LinkedHashMap<String, Outgoing>()

    fun items(): List<Outgoing> = items.values.toList()

    fun pending(sessionId: String? = null): List<Outgoing> =
        items.values.filter { !it.terminal && (sessionId == null || it.sessionId == sessionId) }

    fun get(clientMsgId: String): Outgoing? = items[clientMsgId]

    /** 入箱即进入后悔期。`newId` 由调用方生成（生产用 ULID，测试用固定串）。 */
    fun enqueue(sessionId: String, text: String, newId: () -> String): Outgoing {
        val depth = pending(sessionId).size
        if (depth >= maxDepth) throw QueueFullException(depth)
        val now = clock()
        val item = Outgoing(
            clientMsgId = newId(),
            sessionId = sessionId,
            text = text,
            createdAtMillis = now,
            sendAtMillis = now + graceMillis,
        )
        items[item.clientMsgId] = item
        return item
    }

    /** 后悔期到期、该真正发出去的条目。返回时把它们置为 SENDING。 */
    fun dueToSend(nowMillis: Long = clock()): List<Outgoing> {
        val due = items.values.filter { it.state == OutboxState.GRACE && nowMillis >= it.sendAtMillis }
        due.forEach { items[it.clientMsgId] = it.copy(state = OutboxState.SENDING, attempts = it.attempts + 1) }
        return due.map { items.getValue(it.clientMsgId) }
    }

    fun markQueued(clientMsgId: String, jobId: String?) {
        update(clientMsgId) { it.copy(state = OutboxState.QUEUED, jobId = jobId, error = null) }
    }

    fun markRunning(clientMsgId: String) {
        update(clientMsgId) { it.copy(state = OutboxState.RUNNING) }
    }

    fun markDone(clientMsgId: String) {
        update(clientMsgId) { it.copy(state = OutboxState.DONE, error = null) }
    }

    fun markFailed(clientMsgId: String, error: String) {
        update(clientMsgId) { it.copy(state = OutboxState.FAILED, error = error) }
    }

    fun markCancelled(clientMsgId: String) {
        update(clientMsgId) { it.copy(state = OutboxState.CANCELLED) }
    }

    /**
     * 重发：**复用同一个 `client_msg_id`**。
     *
     * 这是"飞行模式 3 秒再关不产生两条"的关键——服务端按它做幂等，
     * 换 id 就等于发了一条新消息。
     */
    fun retry(clientMsgId: String): Outgoing? {
        val item = items[clientMsgId] ?: return null
        // 只有失败的才能重发：已送达再发会重复，已取消是用户自己的决定
        if (item.state != OutboxState.FAILED) return null
        val retried = item.copy(state = OutboxState.SENDING, attempts = item.attempts + 1, error = null)
        items[clientMsgId] = retried
        return retried
    }

    /** 撤销。**只看状态，不看时间**——时间已经体现在能不能走到 SENDING 上。 */
    fun undo(clientMsgId: String): UndoPlan {
        val item = items[clientMsgId] ?: return UndoPlan.NotFound
        return when (item.state) {
            OutboxState.GRACE -> {
                items.remove(clientMsgId)
                UndoPlan.Drop
            }

            OutboxState.SENDING, OutboxState.QUEUED, OutboxState.RUNNING ->
                UndoPlan.CancelJob(item.jobId)

            else -> UndoPlan.TooLate(item.state)
        }
    }

    /**
     * 编辑 = 删旧 + 建新，**必须换一个新的 `client_msg_id`**，
     * 否则服务端会把它当成重复消息直接忽略（S4 的幂等逻辑）。
     */
    fun edit(clientMsgId: String, newText: String, newId: () -> String): UndoPlan {
        val item = items[clientMsgId] ?: return UndoPlan.NotFound
        if (item.state == OutboxState.GRACE) {
            items[clientMsgId] = item.copy(text = newText)
            return UndoPlan.Drop
        }
        if (item.state == OutboxState.QUEUED || item.state == OutboxState.RUNNING) {
            val replacement = item.copy(
                clientMsgId = newId(),
                text = newText,
                createdAtMillis = clock(),
                sendAtMillis = clock(),   // 编辑后立刻发，不再给一次后悔期
                state = OutboxState.SENDING,
                jobId = null,
                error = null,
                attempts = item.attempts + 1,
            )
            items.remove(clientMsgId)
            items[replacement.clientMsgId] = replacement
            return UndoPlan.CancelJob(item.jobId)
        }
        return UndoPlan.TooLate(item.state)
    }

    /** 服务端回推的终态：把本地条目对齐（重连后补齐时会用到）。 */
    fun reconcile(clientMsgId: String, state: OutboxState) {
        if (items.containsKey(clientMsgId)) update(clientMsgId) { it.copy(state = state) }
    }

    fun clearTerminal() {
        items.entries.removeIf { it.value.terminal }
    }

    private fun update(clientMsgId: String, change: (Outgoing) -> Outgoing) {
        val item = items[clientMsgId] ?: return
        items[clientMsgId] = change(item)
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 3_000L
        const val DEFAULT_MAX_DEPTH = 5
    }
}
