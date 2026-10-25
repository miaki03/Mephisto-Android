package dev.mephisto.app.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * 常驻 SSE 读取：断线按 `Backoff` 重连，带 `Last-Event-ID` 续传。
 *
 * 它只做两件事：把字节流切成行喂给 `SseParser`，以及重连。
 * 解析规则和退避都在纯函数里（`SseParser` / `Backoff`），所以那部分有单测，
 * 这里只剩"开连接、读循环、关连接"——没被测到的就只有 IO 本身。
 */
class SseClient(
    private val baseUrl: String,
    private val token: String,
    private val onEvent: (SseEvent) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 0,      // 0 = 不超时，靠心跳探测
) {
    @Volatile
    private var running = true

    @Volatile
    private var thread: Thread? = null

    private var lastEventId: Long = 0

    fun start(sinceSeq: Long = 0) {
        lastEventId = sinceSeq
        thread = Thread({ loop() }, "mephisto-sse").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        var attempt = 0
        while (running) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/api/v1/events?since_seq=$lastEventId")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    setRequestProperty("Accept", "text/event-stream")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    if (lastEventId > 0) setRequestProperty("Last-Event-ID", lastEventId.toString())
                }
                onStatus("已连接")
                val parser = SseParser()
                connection.inputStream.bufferedReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        val event = parser.feed(line) ?: continue
                        attempt = 0
                        event.id?.let { lastEventId = it }
                        onEvent(event)
                    }
                }
                // 服务端正常结束（比如 max_seconds 到点），立刻重连
                if (running) onStatus("重连中…")
                connection.disconnect()
                Thread.sleep(Backoff.delayMillis(0))
            } catch (interrupted: InterruptedException) {
                return
            } catch (error: Exception) {
                connection?.disconnect()
                if (!running) return
                attempt += 1
                onStatus("断开，${Backoff.delayMillis(attempt) / 1000.0}s 后重连（${error.javaClass.simpleName}）")
                try {
                    Thread.sleep(Backoff.delayMillis(attempt))
                } catch (interrupted: InterruptedException) {
                    return
                }
            }
        }
    }
}
