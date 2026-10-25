package dev.mephisto.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(private val replyOf: (String) -> HttpReply) : HttpTransport {
    var lastUrl: String? = null
    var lastHeaders: Map<String, String> = emptyMap()

    override fun get(url: String, headers: Map<String, String>): HttpReply {
        lastUrl = url
        lastHeaders = headers
        return replyOf(url)
    }
}

private fun ok(body: String) = FakeTransport { HttpReply(200, body) }

private fun api(transport: HttpTransport, base: String = "http://127.0.0.1:8787", token: String = "tok") =
    MephistoApi(transport, base, token)

class HealthTest {
    @Test
    fun `parses healthz payload`() {
        val result = api(ok("""{"ok":true,"version":"0.0.1","uptime_seconds":8,"sessions":3,"pending_notices":2}"""))
            .healthz()
        assertTrue(result is ApiResult.Ok)
        val health = (result as ApiResult.Ok).value
        assertEquals("0.0.1", health.version)
        assertEquals(8, health.uptimeSeconds)
        assertEquals(3, health.sessions)
        assertEquals(2, health.pendingNotices)
    }

    @Test
    fun `healthz url has no trailing slash duplication`() {
        val transport = ok("""{"ok":true}""")
        api(transport, base = "http://host:8787/").healthz()
        assertEquals("http://host:8787/healthz", transport.lastUrl)
    }
}

class SessionListTest {
    @Test
    fun `parses sessions and sends bearer token`() {
        val transport = ok(
            """{"items":[
                {"id":"01A","title":"交表","kind":"chatter","seq":7,"last_active_at":"2026-10-26T07:00:00+00:00"},
                {"id":"01B","title":"报销","kind":"chatter","seq":2}
            ]}""",
        )
        val result = api(transport).sessions()
        assertTrue(result is ApiResult.Ok)
        val items = (result as ApiResult.Ok).value
        assertEquals(2, items.size)
        assertEquals("交表", items[0].title)
        assertEquals(7, items[0].seq)
        assertEquals(null, items[1].lastActiveAt)
        assertEquals("Bearer tok", transport.lastHeaders["Authorization"])
        assertTrue(transport.lastUrl!!.contains("since_seq=0"))
    }

    @Test
    fun `missing items key yields empty list`() {
        val result = api(ok("""{"total":0}""")).sessions()
        assertEquals(emptyList<Session>(), (result as ApiResult.Ok).value)
    }

    @Test
    fun `single session response is unwrapped`() {
        val result = api(ok("""{"session":{"id":"01A","title":"x","seq":1}}""")).session("01A")
        assertEquals("01A", (result as ApiResult.Ok).value.id)
    }
}

class MessageListTest {
    @Test
    fun `parses directions and client msg id`() {
        val transport = ok(
            """{"items":[
                {"id":"m1","seq":1,"direction":"in","text":"你好","created_at":"t1","client_msg_id":"c_1"},
                {"id":"m2","seq":2,"direction":"out","text":"收到","created_at":"t2"}
            ]}""",
        )
        val result = api(transport).messages("01A")
        val items = (result as ApiResult.Ok).value
        assertEquals(2, items.size)
        assertEquals(false, items[0].isOutgoing)
        assertEquals(true, items[1].isOutgoing)
        assertEquals("c_1", items[0].clientMsgId)
        assertEquals(null, items[1].clientMsgId)
        assertTrue(transport.lastUrl!!.contains("/api/v1/sessions/01A/messages"))
    }
}

class ErrorMappingTest {
    @Test
    fun `401 maps to unauthorized with a readable hint`() {
        val result = api(FakeTransport { HttpReply(401, """{"error":{"code":"unauthorized","message":"缺少或无效的 Bearer 令牌"}}""") })
            .sessions()
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals("unauthorized", failure.code)
        assertEquals(401, failure.httpStatus)
        assertTrue(failure.readable.contains("令牌"))
    }

    @Test
    fun `network exception becomes network failure`() {
        val result = api(FakeTransport { throw java.net.UnknownHostException("no dns") }).healthz()
        val failure = result as ApiResult.Failure
        assertEquals("network", failure.code)
        assertTrue(failure.message.contains("UnknownHost"))
    }

    @Test
    fun `broken payload becomes bad_payload`() {
        val result = api(ok("<html>not json</html>")).sessions()
        assertEquals("bad_payload", (result as ApiResult.Failure).code)
    }

    @Test
    fun `blank base url fails fast without touching transport`() {
        var called = false
        val transport = FakeTransport { called = true; HttpReply(200, "{}") }
        val result = MephistoApi(transport, "", "tok").healthz()
        assertEquals("no_base_url", (result as ApiResult.Failure).code)
        assertEquals(false, called)
    }

    @Test
    fun `error body that is not the expected shape still yields a code`() {
        val (code, message) = Parsers.error("""{"message":"nope"}""")
        assertEquals("bad_error", code)
        assertTrue(message.contains("nope"))
    }
}

class NoticeParseTest {
    @Test
    fun `parses notices for the alarm layer`() {
        val result = api(
            ok("""{"items":[{"id":"n1","kind":"reminder","title":"交表","body":null,"due_utc":"2026-10-26T09:00:00+00:00","state":"pending"}]}"""),
        ).notices()
        val notices = (result as ApiResult.Ok).value
        assertEquals(1, notices.size)
        assertEquals("reminder", notices[0].kind)
        assertEquals(null, notices[0].body)
        assertEquals("2026-10-26T09:00:00+00:00", notices[0].dueUtc)
        assertEquals("pending", notices[0].state)
    }
}
