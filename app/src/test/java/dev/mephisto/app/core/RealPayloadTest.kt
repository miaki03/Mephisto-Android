package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真服务端抓下来的响应**当夹具。
 *
 * 手写 JSON 只能证明解析器符合我的想象；这组夹具是 `mephisto serve` 真实返回的字节，
 * 能抓到"服务端字段改了、客户端还在读旧名字"这类问题。
 * 抓取方法见 README（`state/capture.py` 的做法：带令牌 GET 四个端点，原样落盘）。
 */
class RealPayloadTest {
    private fun fixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name.json")
            ?: error("缺少夹具 fixtures/$name.json")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `healthz fixture parses`() {
        val health = Parsers.health(fixture("healthz"))
        assertTrue(health.ok)
        assertTrue(health.version.isNotBlank())
        assertTrue(health.pendingNotices >= 0)
    }

    @Test
    fun `sessions fixture parses with ulid ids`() {
        val sessions = Parsers.sessions(fixture("sessions"))
        assertTrue(sessions.isNotEmpty())
        for (session in sessions) {
            assertEquals("ULID 应为 26 位：${session.id}", 26L, session.id.length.toLong())
            assertTrue(session.title.isNotBlank())
        }
    }

    @Test
    fun `messages fixture parses both directions`() {
        val messages = Parsers.messages(fixture("messages"))
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.any { it.isOutgoing })
        assertTrue(messages.any { !it.isOutgoing })
    }

    @Test
    fun `notices fixture parses`() {
        val notices = Parsers.notices(fixture("notices"))
        assertTrue(notices.isNotEmpty())
        for (notice in notices) {
            assertEquals("pending", notice.state)
            assertTrue(notice.dueUtc.contains("T"))
        }
    }

    @Test
    fun `fixtures drive the api end to end`() {
        val fixtures = mapOf(
            "/healthz" to fixture("healthz"),
            "/api/v1/sessions" to fixture("sessions"),
            "/api/v1/sessions/" to fixture("messages"),
            "/api/v1/notices" to fixture("notices"),
        )
        val transport = HttpTransport { url, _ ->
            val match = fixtures.entries.firstOrNull { url.contains(it.key) }
            HttpReply(if (match == null) 404 else 200, match?.value ?: "")
        }
        val api = MephistoApi(transport, "http://fixture.local", "tok")

        assertTrue(api.healthz() is ApiResult.Ok)
        assertTrue(api.sessions() is ApiResult.Ok)
        assertTrue(api.messages("01M5S7EQHFQA10MR0S5SPK779J") is ApiResult.Ok)
        assertTrue(api.notices() is ApiResult.Ok)
    }
}
