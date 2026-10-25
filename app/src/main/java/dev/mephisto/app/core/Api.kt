package dev.mephisto.app.core

import java.net.HttpURLConnection
import java.net.URL

/** 一次 HTTP 往返的最小形状。抽象出来是为了让单测不碰网络。 */
data class HttpReply(val status: Int, val body: String)

interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpReply
    fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

/** 用 JDK 自带的 `HttpURLConnection`：零依赖，JVM 单测里也能跑。 */
class UrlConnectionTransport(private val timeoutMillis: Int = 10_000) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpReply =
        exchange(url, headers, body = null)

    override fun post(url: String, headers: Map<String, String>, body: String): HttpReply =
        exchange(url, headers, body = body)

    private fun exchange(url: String, headers: Map<String, String>, body: String?): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpReply(code, text)
        } finally {
            connection.disconnect()
        }
    }
}

/** 结果类型：要么拿到值，要么拿到一句能显示给人看的话。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class Failure(
        val code: String,
        val message: String,
        val httpStatus: Int = 0,
    ) : ApiResult<Nothing> {
        /** 给界面用的一句话。 */
        val readable: String
            get() = Protocol.ERROR_HINTS[code]?.let { "$it（$code）" } ?: "失败：$message（$code）"
    }
}

/** `POST /messages` 的 202 响应：消息已落库、任务已排队。 */
data class PostReceipt(
    val message: Message,
    val jobId: String?,
    val queued: Boolean,
    val duplicate: Boolean,
)

/**
 * HTTP 接口。只读四个 + 发送 + 取消任务。
 *
 * 上传统计上传（分片）不在这里：那是 S9 里可以独立拆出去的一块，
 * 先把"发一句话、看到进度、能打断"这条主链路做对。
 */
class MephistoApi(
    private val transport: HttpTransport,
    baseUrl: String,
    private val token: String = "",
    private val pageSize: Int = 100,
) {
    private val base = baseUrl.trim().trimEnd('/')

    fun healthz(): ApiResult<Health> = call("/healthz") { Parsers.health(it) }

    fun sessions(sinceSeq: Int = 0): ApiResult<List<Session>> =
        call("/api/v1/sessions?since_seq=$sinceSeq&limit=$pageSize") { Parsers.sessions(it) }

    fun session(sessionId: String): ApiResult<Session> =
        call("/api/v1/sessions/${encode(sessionId)}") { Parsers.session(it) }

    fun messages(sessionId: String, sinceSeq: Int = 0): ApiResult<List<Message>> =
        call("/api/v1/sessions/${encode(sessionId)}/messages?since_seq=$sinceSeq&limit=$pageSize") {
            Parsers.messages(it)
        }

    fun notices(untilDays: Int = 7): ApiResult<List<Notice>> =
        call("/api/v1/notices?until_days=$untilDays") { Parsers.notices(it) }

    /**
     * 发一条消息。`clientMsgId` 由客户端生成，**重试时必须复用**——
     * 服务端按它做幂等，换一个就等于又发了一条。
     */
    fun postMessage(sessionId: String, text: String, clientMsgId: String): ApiResult<PostReceipt> =
        callPost(
            "/api/v1/sessions/${encode(sessionId)}/messages",
            """{"text":${quote(text)},"client_msg_id":${quote(clientMsgId)}}""",
        ) { Parsers.receipt(it) }

    /**
     * 取消任务：排队中删行，运行中杀进程树。
     * 返回 `queued` / `interrupted`；`too_late` 走失败分支（409）。
     */
    fun cancelJob(jobId: String): ApiResult<String> =
        callPost("/api/v1/jobs/${encode(jobId)}/cancel", "{}") { body ->
            org.json.JSONObject(body).optString("result", "unknown")
        }

    // ---------- 内部 ----------
    private fun <T> call(path: String, parse: (String) -> T): ApiResult<T> {
        if (base.isEmpty()) return ApiResult.Failure("no_base_url", "还没填服务端地址")
        val reply = try {
            transport.get(base + path, authHeaders())
        } catch (error: Exception) {
            return ApiResult.Failure("network", "${error.javaClass.simpleName}: ${error.message}")
        }
        return interpret(reply, parse)
    }

    private fun <T> callPost(path: String, body: String, parse: (String) -> T): ApiResult<T> {
        if (base.isEmpty()) return ApiResult.Failure("no_base_url", "还没填服务端地址")
        val reply = try {
            transport.post(base + path, authHeaders(), body)
        } catch (error: Exception) {
            return ApiResult.Failure("network", "${error.javaClass.simpleName}: ${error.message}")
        }
        return interpret(reply, parse)
    }

    private fun <T> interpret(reply: HttpReply, parse: (String) -> T): ApiResult<T> = when {
        reply.status in 200..299 -> {
            try {
                ApiResult.Ok(parse(reply.body))
            } catch (error: Exception) {
                ApiResult.Failure("bad_payload", "响应格式不对：${error.message}", reply.status)
            }
        }

        else -> {
            val (code, message) = Parsers.error(reply.body)
            ApiResult.Failure(code, message, reply.status)
        }
    }

    private fun authHeaders(): Map<String, String> =
        if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    /** 路径里的 id 要转义：ULID 本身安全，但别指望上游永远守规矩。 */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** 最小 JSON 字符串转义——只处理消息正文，不做通用序列化。 */
    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append(String.format("\\u%04x", character.code)) else append(character)
            }
        }
        append('"')
    }
}
