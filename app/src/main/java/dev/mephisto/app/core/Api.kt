package dev.mephisto.app.core

import java.net.HttpURLConnection
import java.net.URL

/** 一次 HTTP 往返的最小形状。抽象出来是为了让单测不碰网络。 */
data class HttpReply(val status: Int, val body: String)

fun interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpReply
}

/** 用 JDK 自带的 `HttpURLConnection`：零依赖，JVM 单测里也能跑。 */
class UrlConnectionTransport(private val timeoutMillis: Int = 10_000) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpReply(code, body)
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

/**
 * 只读接口：`/healthz`、会话列表、历史消息、通知。
 *
 * 不发消息——发送在 S9，那要处理排队、三级撤销和 SSE，和只读是两套复杂度。
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

    private fun <T> call(path: String, parse: (String) -> T): ApiResult<T> {
        if (base.isEmpty()) {
            return ApiResult.Failure("no_base_url", "还没填服务端地址")
        }
        val reply = try {
            transport.get(
                base + path,
                if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"),
            )
        } catch (error: Exception) {
            return ApiResult.Failure(
                "network",
                "${error.javaClass.simpleName}: ${error.message}",
            )
        }
        return when {
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
    }

    /** 路径里的会话 id 要转义：ULID 本身安全，但别指望上游永远守规矩。 */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
