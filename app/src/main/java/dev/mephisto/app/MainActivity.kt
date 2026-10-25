package dev.mephisto.app

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mephisto.app.core.ApiResult
import dev.mephisto.app.core.Message
import dev.mephisto.app.core.MephistoApi
import dev.mephisto.app.core.Session
import dev.mephisto.app.core.UrlConnectionTransport
import kotlin.concurrent.thread

private const val PREFS = "mephisto"
private const val KEY_BASE = "base_url"
private const val KEY_TOKEN = "token"
private const val DEFAULT_BASE = "http://127.0.0.1:8787"

/**
 * 第 8 步：只读。/healthz、会话列表、历史消息。
 *
 * 不在这里放发消息、SSE、闹钟——那三件是 S9/S10 的事，混进来会让这一屏的失败原因
 * 变得没法归因。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            MaterialTheme {
                App(prefs)
            }
        }
    }
}

@Composable
private fun App(prefs: SharedPreferences) {
    var baseUrl by remember { mutableStateOf(prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "") }
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var current by remember { mutableStateOf<Session?>(null) }
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var status by remember { mutableStateOf("填好服务端地址与令牌，然后点「刷新会话」") }
    var busy by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val api = remember(baseUrl, token) { MephistoApi(UrlConnectionTransport(), baseUrl, token) }

    fun persist() {
        prefs.edit().putString(KEY_BASE, baseUrl).putString(KEY_TOKEN, token).apply()
    }

    fun loadSessions() {
        if (busy) return
        busy = true
        status = "读取会话…"
        persist()
        val client = api
        thread(name = "sessions") {
            val result = client.sessions()
            main.post {
                busy = false
                when (result) {
                    is ApiResult.Ok -> {
                        sessions = result.value
                        status = "共 ${result.value.size} 个会话"
                    }

                    is ApiResult.Failure -> status = result.readable
                }
            }
        }
    }

    fun openSession(session: Session) {
        if (busy) return
        busy = true
        current = session
        messages = emptyList()
        status = "读取「${session.title}」…"
        val client = api
        thread(name = "messages") {
            val result = client.messages(session.id)
            main.post {
                busy = false
                when (result) {
                    is ApiResult.Ok -> {
                        messages = result.value
                        status = "${messages.size} 条消息"
                    }

                    is ApiResult.Failure -> status = result.readable
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (token.isNotBlank()) loadSessions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mephisto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(status, style = MaterialTheme.typography.bodySmall)

        if (current == null) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("服务端地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer 令牌") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { loadSessions() }, enabled = !busy) { Text("刷新会话") }
            }
            HorizontalDivider()
            if (sessions.isEmpty()) {
                Text("还没有会话。先在服务端建一个（或从调试页建）。", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sessions, key = { it.id }) { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openSession(session) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "seq ${session.seq} · ${session.lastActiveAt ?: "无活动时间"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        } else {
            TextButton(onClick = { current = null; messages = emptyList() }) { Text("← 返回会话列表") }
            Text(current!!.title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (messages.isEmpty()) {
                Text("（这个会话还没有消息）")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(messages, key = { it.id }) { message ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                if (message.isOutgoing) "Agent" else "我",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
