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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import dev.mephisto.app.core.Outbox
import dev.mephisto.app.core.OutboxState
import dev.mephisto.app.core.ProgressTracker
import dev.mephisto.app.core.QueueFullException
import dev.mephisto.app.core.Session
import dev.mephisto.app.core.SseClient
import dev.mephisto.app.core.UndoPlan
import dev.mephisto.app.core.UrlConnectionTransport
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

private const val PREFS = "mephisto"
private const val KEY_BASE = "base_url"
private const val KEY_TOKEN = "token"
private const val DEFAULT_BASE = "http://127.0.0.1:8787"

/** `client_msg_id`：重试时必须复用，所以它只在"新消息"时生成一次。 */
private fun newClientMsgId(): String =
    "c_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

/**
 * 第 8 步的只读 + 第 9 步的发送 / 排队 / 三级撤销 / 进度 / 打断。
 *
 * 这里只做"把状态画出来"和"把点击转成意图"，判断全在 `core/` 里——
 * 那部分有 53 个 JVM 单测，这一层没有。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Root(prefs)
            }
        }
    }
}

/** 顶层只有两屏：主流程（会话/对话）与"提醒与权限"。 */
@Composable
private fun Root(prefs: SharedPreferences) {
    var showSystem by remember { mutableStateOf(false) }
    if (showSystem) {
        dev.mephisto.app.ui.SystemScreen(androidx.compose.ui.platform.LocalContext.current) {
            showSystem = false
        }
    } else {
        App(prefs, onOpenSystem = { showSystem = true })
    }
}

@Composable
private fun App(prefs: SharedPreferences, onOpenSystem: () -> Unit) {
    var baseUrl by remember { mutableStateOf(prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "") }
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var current by remember { mutableStateOf<Session?>(null) }
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("填好地址与令牌，然后刷新会话") }
    var sseStatus by remember { mutableStateOf("未连接") }
    var busy by remember { mutableStateOf(false) }
    var boxVersion by remember { mutableIntStateOf(0) }
    var progressVersion by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val outbox = remember { Outbox(clock = { System.currentTimeMillis() }) }
    val progress = remember { ProgressTracker(clock = { System.currentTimeMillis() }) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val api = remember(baseUrl, token) { MephistoApi(UrlConnectionTransport(), baseUrl, token) }

    fun persist() = prefs.edit().putString(KEY_BASE, baseUrl).putString(KEY_TOKEN, token).apply()

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

    fun reloadMessages(sessionId: String) {
        val client = api
        thread(name = "messages") {
            val result = client.messages(sessionId)
            main.post {
                if (result is ApiResult.Ok && current?.id == sessionId) {
                    messages = result.value
                    boxVersion++
                }
            }
        }
    }

    fun openSession(session: Session) {
        current = session
        messages = emptyList()
        status = "读取「${session.title}」…"
        val client = api
        thread(name = "messages") {
            val result = client.messages(session.id)
            main.post {
                when (result) {
                    is ApiResult.Ok -> {
                        messages = result.value
                        status = "${result.value.size} 条消息"
                    }

                    is ApiResult.Failure -> status = result.readable
                }
            }
        }
    }

    fun send() {
        val session = current ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        try {
            outbox.enqueue(session.id, text, ::newClientMsgId)
            draft = ""
            boxVersion++
        } catch (full: QueueFullException) {
            status = "已排 ${full.depth} 条，等这轮跑完再发"
        }
    }

    fun undo(clientMsgId: String) {
        when (val plan = outbox.undo(clientMsgId)) {
            UndoPlan.Drop -> status = "已撤销（还没发出去）"
            is UndoPlan.CancelJob -> {
                status = "正在撤回…"
                val jobId = plan.jobId
                val client = api
                thread(name = "cancel") {
                    val outcome = if (jobId == null) null else client.cancelJob(jobId)
                    main.post {
                        when (outcome) {
                            null -> outbox.markCancelled(clientMsgId)
                            is ApiResult.Ok -> outbox.markCancelled(clientMsgId)
                            is ApiResult.Failure -> {
                                outbox.markFailed(clientMsgId, outcome.readable)
                                status = outcome.readable
                            }
                        }
                        boxVersion++
                    }
                }
            }

            is UndoPlan.TooLate -> status = "已经结束了，撤不回"
            UndoPlan.NotFound -> Unit
        }
        boxVersion++
    }

    // 进 App 就自动拉一次：没有这个，用户会以为"没数据"，其实只是没人点刷新
    LaunchedEffect(token) {
        if (token.isNotBlank()) loadSessions()
    }

    // 200ms 一跳：后悔期倒计时和秒表都靠它
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    // 后悔期到点 → 真正发出去
    LaunchedEffect(now) {
        val due = outbox.dueToSend(now)
        if (due.isEmpty()) return@LaunchedEffect
        boxVersion++
        for (item in due) {
            val client = api
            thread(name = "send") {
                val result = client.postMessage(item.sessionId, item.text, item.clientMsgId)
                main.post {
                    when (result) {
                        is ApiResult.Ok -> {
                            outbox.markQueued(item.clientMsgId, result.value.jobId)
                            if (current?.id == item.sessionId) progress.start(result.value.jobId)
                        }

                        is ApiResult.Failure -> outbox.markFailed(item.clientMsgId, result.readable)
                    }
                    boxVersion++
                    progressVersion++
                }
            }
        }
    }

    // SSE：只在打开某个会话时连；换会话就断开重连
    DisposableEffect(current?.id, baseUrl, token) {
        val session = current
        if (session == null) {
            onDispose { }
        } else {
            val client = SseClient(
                baseUrl = baseUrl,
                token = token,
                onEvent = { event ->
                    when (event.kind) {
                        "job" -> {
                            if (progress.apply(event.data)) progressVersion++
                            if (progress.state == "done" || progress.state == "failed" || progress.state == "cancelled") {
                                main.post { progressVersion++ }
                            }
                        }

                        "message" -> main.post {
                            progressVersion++
                            reloadMessages(session.id)
                        }

                        else -> Unit
                    }
                },
                onStatus = { text -> main.post { sseStatus = text } },
            )
            client.start(0)
            onDispose { client.stop() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Mephisto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(status, style = MaterialTheme.typography.bodySmall)

        val session = current
        if (session == null) {
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
                OutlinedButton(onClick = onOpenSystem) { Text("提醒与权限") }
            }
            HorizontalDivider()
            if (sessions.isEmpty()) {
                Text("还没有会话。先在服务端建一个。", style = MaterialTheme.typography.bodyMedium)
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
                            Text("seq ${session.seq}", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        } else {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = {
                current = null
                messages = emptyList()
                progress.finish()
            }) { Text("← 返回") }
            Text(session.title, style = MaterialTheme.typography.titleMedium)
        }

        val progressLine = remember(progressVersion, now) { progress.statusLine(now) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(progressLine, style = MaterialTheme.typography.bodySmall)
            if (progress.working()) {
                TextButton(onClick = {
                    val jobId = progress.jobId
                    val client = api
                    if (jobId != null) {
                        thread(name = "interrupt") {
                            val outcome = client.cancelJob(jobId)
                            main.post {
                                status = when (outcome) {
                                    is ApiResult.Ok -> "已打断（${outcome.value}）"
                                    is ApiResult.Failure -> outcome.readable
                                }
                                progressVersion++
                            }
                        }
                    }
                }) { Text("打断") }
            }
        }
        Text("事件流：$sseStatus", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()

        val pendingItems = remember(boxVersion) {
            outbox.items().filter { it.sessionId == session.id && !it.terminal }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages, key = { it.id }) { message ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        if (message.isOutgoing) "Agent" else "我",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(pendingItems.map { it.clientMsgId }) { id ->
                val item = outbox.get(id) ?: return@items
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        Text(item.badge(now), style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { undo(item.clientMsgId) }) { Text("撤销") }
                }
            }
        }

        if (outbox.items().any { it.state == OutboxState.FAILED && it.sessionId == session.id }) {
            Text("有发送失败的消息，点「撤销」后可重新输入", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("说点什么（3 秒内可撤销）") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { send() }) { Text("发送") }
        }
        Text("已在发件箱 ${outbox.pending(session.id).size}/5 条", style = MaterialTheme.typography.labelSmall)
        }
    }
}
