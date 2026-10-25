package dev.mephisto.app.core

/**
 * 发件箱：本地对"这条消息现在到哪一步了"的记账。
 *
 * 按用户要求**去掉 3 秒后悔期**——按下发送就是发出去，不再有"3 秒内随时撤回"的中间态。
 * 理由：它挡在每条消息前面，换来的却是一个几乎没人用的窗口；
 * 真正需要反悔时，"排队中取消 / 运行中打断"已经够了。
 *
 * ```
 * SENDING --(202)--> QUEUED --(SSE)--> RUNNING --> DONE
 *    |                  |                |
 *    +--失败--> FAILED   +--取消--> CANCELLED  +--打断--> CANCELLED
 * ```
 */
enum class OutboxState { SENDING, QUEUED, RUNNING, DONE, FAILED, CANCELLED }

val TERMINAL_STATES = setOf(OutboxState.DONE, OutboxState.FAILED, OutboxState.CANCELLED)

data class Outgoing(
    val clientMsgId: String,
    val sessionId: String,
    val text: String,
    val createdAtMillis: Long,
    val state: OutboxState = OutboxState.SENDING,
    val jobId: String? = null,
    val error: String? = null,
    val attempts: Int = 1,
) {
    val terminal: Boolean get() = state in TERMINAL_STATES

    /** 界面角标。没有倒计时了，只报状态。 */
    fun badge(): String = when (state) {
        OutboxState.SENDING -> "发送中"
        OutboxState.QUEUED -> "已排队"
        OutboxState.RUNNING -> "执行中"
        OutboxState.DONE -> "已送达"
        OutboxState.FAILED -> "失败"
        OutboxState.CANCELLED -> "已取消"
    }
}

/** 取消的三种结局。 */
sealed interface UndoPlan {
    /** 已提交：让服务端删行（排队中）或杀进程树（运行中）。 */
    data class CancelJob(val jobId: String?) : UndoPlan

    /** 已经结束，来不及了。 */
    data class TooLate(val state: OutboxState) : UndoPlan

    data object NotFound : UndoPlan
}

class QueueFullException(val depth: Int) : Exception("排队已满（$depth 条）")

/** 发件箱。按会话隔离：不同会话互不影响，同一会话才谈"排队"。 */
class Outbox(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private val items = LinkedHashMap<String, Outgoing>()

    fun items(): List<Outgoing> = items.values.toList()

    fun pending(sessionId: String? = null): List<Outgoing> =
        items.values.filter { !it.terminal && (sessionId == null || it.sessionId == sessionId) }

    fun get(clientMsgId: String): Outgoing? = items[clientMsgId]

    /** 入箱即"正在发"。`newId` 由调用方生成（生产用随机 id，测试用固定串）。 */
    fun enqueue(sessionId: String, text: String, newId: () -> String): Outgoing {
        val depth = pending(sessionId).size
        if (depth >= maxDepth) throw QueueFullException(depth)
        val item = Outgoing(
            clientMsgId = newId(),
            sessionId = sessionId,
            text = text,
            createdAtMillis = System.currentTimeMillis(),
        )
        items[item.clientMsgId] = item
        return item
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
     * 这是"断网重连不产生两条"的关键——服务端按它做幂等，换 id 就成了新消息。
     */
    fun retry(clientMsgId: String): Outgoing? {
        val item = items[clientMsgId] ?: return null
        // 只有失败的能重发：已送达再发会重复，已取消是用户自己的决定
        if (item.state != OutboxState.FAILED) return null
        val retried = item.copy(state = OutboxState.SENDING, attempts = item.attempts + 1, error = null)
        items[clientMsgId] = retried
        return retried
    }

    /** 取消。只剩"已提交之后"的两段：排队中删行、运行中打断。 */
    fun cancel(clientMsgId: String): UndoPlan {
        val item = items[clientMsgId] ?: return UndoPlan.NotFound
        return when (item.state) {
            OutboxState.SENDING, OutboxState.QUEUED, OutboxState.RUNNING -> UndoPlan.CancelJob(item.jobId)
            else -> UndoPlan.TooLate(item.state)
        }
    }

    /** 服务端回推的终态：把本地条目对齐。 */
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
        const val DEFAULT_MAX_DEPTH = 5
    }
}
