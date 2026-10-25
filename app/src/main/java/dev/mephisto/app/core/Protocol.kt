package dev.mephisto.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 与服务端 step-4 冻结的契约一一对应。
 *
 * 这里刻意不引入序列化框架：目前只解析三种响应（healthz / sessions / messages），
 * 用 `org.json` 手写解析反而更容易在字段缺失时给出明确错误，也少一个依赖和一套注解。
 * 解析代码在 JVM 单测里跑，不依赖真机。
 */
object Protocol {
    const val CHANNEL = "http"

    /** 认得的错误码，用于把失败翻译成人能看懂的一句话。 */
    val ERROR_HINTS = mapOf(
        "unauthorized" to "令牌无效或已被吊销",
        "session_not_found" to "会话不存在",
        "empty_message" to "消息不能为空",
        "illegal_transition" to "这个状态下不能这么做",
        "incomplete_upload" to "上传没传完",
        "digest_mismatch" to "文件内容与声明不符",
        "body_too_large" to "请求体太大",
        "internal" to "服务端内部错误",
    )
}

// ---------- 模型 ----------

data class Health(
    val ok: Boolean,
    val version: String,
    val uptimeSeconds: Int,
    val sessions: Int,
    val pendingNotices: Int,
)

data class Session(
    val id: String,
    val title: String,
    val kind: String,
    val seq: Int,
    val lastActiveAt: String?,
)

data class Message(
    val id: String,
    val seq: Int,
    val direction: String,
    val text: String,
    val createdAt: String,
    val clientMsgId: String?,
) {
    val isOutgoing: Boolean get() = direction == "out"
}

/** `notice`（提醒的投递单元）。S10 的闹钟要读它。 */
data class Notice(
    val id: String,
    val kind: String,
    val title: String,
    val body: String?,
    val dueUtc: String,
    val state: String,
)

// ---------- 解析 ----------

object Parsers {
    fun health(body: String): Health {
        val root = JSONObject(body)
        return Health(
            ok = root.optBoolean("ok", false),
            version = root.optString("version", ""),
            uptimeSeconds = root.optInt("uptime_seconds", 0),
            sessions = root.optInt("sessions", 0),
            pendingNotices = root.optInt("pending_notices", 0),
        )
    }

    fun sessions(body: String): List<Session> {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { index -> session(items.getJSONObject(index)) }
    }

    fun session(body: String): Session {
        val root = JSONObject(body)
        return session(root.getJSONObject("session"))
    }

    fun sessionsFromCreate(body: String): Session = session(body)

    fun messages(body: String): List<Message> {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { index -> message(items.getJSONObject(index)) }
    }

    /** POST /messages 的 202：消息本身 + 排队信息。 */
    fun receipt(body: String): PostReceipt {
        val root = JSONObject(body)
        return PostReceipt(
            message = message(root.getJSONObject("message")),
            jobId = root.optString("job_id").ifEmpty { null },
            queued = root.optBoolean("queued", false),
            duplicate = root.optBoolean("duplicate", false),
        )
    }

    fun notices(body: String): List<Notice> {
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            Notice(
                id = item.getString("id"),
                kind = item.optString("kind", ""),
                title = item.optString("title", ""),
                body = item.optString("body").ifEmpty { null },
                dueUtc = item.optString("due_utc", ""),
                state = item.optString("state", ""),
            )
        }
    }

    /** 服务端的错误体固定是 `{"error":{"code":...,"message":...}}`。 */
    fun error(body: String?): Pair<String, String> {
        if (body.isNullOrBlank()) return "empty_body" to "服务端返回了空响应"
        return try {
            val error = JSONObject(body).optJSONObject("error")
                ?: return "bad_error" to body.take(200)
            (error.optString("code", "unknown")) to (error.optString("message", ""))
        } catch (failure: Exception) {
            "bad_error" to body.take(200)
        }
    }

    private fun session(item: JSONObject) = Session(
        id = item.getString("id"),
        title = item.optString("title", ""),
        kind = item.optString("kind", "chatter"),
        seq = item.optInt("seq", 0),
        lastActiveAt = item.optString("last_active_at").ifEmpty { null },
    )

    private fun message(item: JSONObject) = Message(
        id = item.getString("id"),
        seq = item.optInt("seq", 0),
        direction = item.optString("direction", "in"),
        text = item.optString("text", ""),
        createdAt = item.optString("created_at", ""),
        clientMsgId = item.optString("client_msg_id").ifEmpty { null },
    )
}
