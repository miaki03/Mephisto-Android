package dev.mephisto.app.core

/**
 * SSE 的行解析与重连策略。
 *
 * 为什么不用 okhttp-sse：我们只需要**解析行**和**带着 Last-Event-ID 重连**这两件事，
 * 而这两件恰好是纯逻辑，能在 JVM 单测里跑。传输仍是 `HttpURLConnection`。
 *
 * 格式（每条事件以空行结束）：
 * ```
 * id: 42
 * event: message
 * data: {"message":{...}}
 * ```
 * `:` 开头的行是注释（心跳），必须忽略而不是报错。
 */
data class SseEvent(val id: Long?, val kind: String, val data: String)

class SseParser {
    private var id: Long? = null
    private var kind: String? = null
    private val data = StringBuilder()

    /** 喂一行，遇到空行且攒了数据就吐事件。 */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty()) {
                kind = null
                return null
            }
            val event = SseEvent(id = id, kind = kind ?: "message", data = data.toString().trimEnd('\n'))
            data.setLength(0)
            kind = null
            return event
        }
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "id" -> id = value.toLongOrNull() ?: id
            "event" -> kind = value
            "data" -> data.append(value).append('\n')
            // retry / 其它字段：协议允许，忽略即可
        }
        return null
    }

    /** 记录到的最后事件号，重连时作为 `Last-Event-ID`。 */
    fun lastEventId(): Long? = id

    fun reset() {
        id = null
        kind = null
        data.setLength(0)
    }
}

/**
 * 断线重连退避：0.5s 起翻倍，封顶 30s。
 *
 * 不用固定 1 秒：手机在电梯里断断续续时，固定间隔会把电池和流量一起烧掉。
 */
object Backoff {
    private const val BASE_MILLIS = 500L
    private const val CAP_MILLIS = 30_000L

    fun delayMillis(attempt: Int): Long {
        if (attempt <= 0) return BASE_MILLIS
        var delay = BASE_MILLIS
        repeat(attempt) {
            delay *= 2
            if (delay >= CAP_MILLIS) return CAP_MILLIS
        }
        return delay
    }
}

/**
 * 把 `job` 事件流翻译成一行给人看的状态。
 *
 * 「思考中」必须是**真的在跑**的证据：秒表 + 当前阶段 + token 数，
 * 而不是一个永远转的假动画——用户要能区分"在干活"和"卡死了"。
 */
class ProgressTracker(private val clock: () -> Long) {
    var jobId: String? = null
        private set
    var state: String = "idle"
        private set
    var phase: String? = null
        private set
    var tokens: Int? = null
        private set
    var cacheRead: Int? = null
        private set
    var error: String? = null
        private set

    private var startedAtMillis: Long = 0
    private var queuedAtMillis: Long = 0

    fun start(jobId: String?) {
        this.jobId = jobId
        state = "queued"
        queuedAtMillis = clock()
        startedAtMillis = 0
        phase = null
        tokens = null
        cacheRead = null
        error = null
    }

    fun finish() {
        state = "idle"
        jobId = null
        phase = null
    }

    /** 吃一条 `job` 事件的 data（JSON）。返回是否改动过状态。 */
    fun apply(json: String): Boolean {
        val payload = try {
            org.json.JSONObject(json)
        } catch (failure: Exception) {
            return false
        }
        // 服务端在 job 事件里直接放字段，也可能包一层 {"job": {...}}
        val body = payload.optJSONObject("job") ?: payload
        val next = body.optString("state")
        if (next.isEmpty()) return false

        state = next
        if (body.has("job_id") && jobId == null) jobId = body.optString("job_id")
        if (next == "running") {
            if (startedAtMillis == 0L) startedAtMillis = clock()
            phase = body.optString("phase").ifEmpty { phase }
            val usage = body.optJSONObject("usage")
            if (usage != null) {
                tokens = usage.optInt("totalTokens", tokens ?: 0)
                cacheRead = usage.optInt("cacheReadTokens", cacheRead ?: 0)
            }
        }
        if (next == "failed") error = body.optString("error").ifEmpty { "未知错误" }
        return true
    }

    fun working(): Boolean = state == "queued" || state == "running"

    /** 一行状态，界面直接显示。 */
    fun statusLine(nowMillis: Long = clock()): String = when (state) {
        "idle" -> "空闲"
        "queued" -> "已排队 ${seconds(nowMillis - queuedAtMillis)}s"
        "running" -> {
            val elapsed = if (startedAtMillis == 0L) 0 else (nowMillis - startedAtMillis) / 1000
            buildString {
                append("执行中 ")
                append(elapsed)
                append("s")
                phase?.let { append(" · ").append(phaseLabel(it)) }
                tokens?.let {
                    append(" · ").append(it).append(" tokens")
                    cacheRead?.let { cached -> append(" / 缓存 ").append(cached) }
                }
            }
        }

        "done" -> "完成"
        "failed" -> "失败：${error ?: "未知"}"
        "cancelled" -> "已打断"
        else -> state
    }

    private fun seconds(millis: Long): Long = (millis / 1000).coerceAtLeast(0)

    private fun phaseLabel(phase: String): String = when (phase) {
        "turn_start" -> "开始"
        "step_start" -> "思考"
        "step_end" -> "一步结束"
        "turn_end" -> "收尾"
        else -> phase
    }
}
