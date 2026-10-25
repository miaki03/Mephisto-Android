package dev.mephisto.app

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mephisto.app.core.ApiResult
import dev.mephisto.app.core.Message
import dev.mephisto.app.core.MephistoApi
import dev.mephisto.app.core.Outbox
import dev.mephisto.app.core.ProgressTracker
import dev.mephisto.app.core.Parsers
import dev.mephisto.app.core.QueueFullException
import dev.mephisto.app.core.Session
import dev.mephisto.app.core.SseClient
import dev.mephisto.app.core.UndoPlan
import dev.mephisto.app.core.UrlConnectionTransport
import dev.mephisto.app.ui.SystemScreen
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

private const val PREFS = "mephisto"
private const val KEY_BASE = "base_url"
private const val KEY_TOKEN = "token"
private const val DEFAULT_BASE = "http://127.0.0.1:8787"

/** `client_msg_id`：重试时必须复用，所以它只在"新消息"时生成一次。 */
private fun newClientMsgId(): String =
    "c_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            MephistoTheme {
                Root(prefs)
            }
        }
    }
}

@Composable
private fun MephistoTheme(content: @Composable () -> Unit) {
    // 跟随系统深浅色：之前用 MaterialTheme() 的默认浅色，深色模式下整屏发灰
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun Root(prefs: SharedPreferences) {
    var showSystem by remember { mutableStateOf(false) }
    if (showSystem) {
        BackHandler { showSystem = false }
        SystemScreen(LocalContext.current) { showSystem = false }
    } else {
        App(prefs, onOpenSystem = { showSystem = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(prefs: SharedPreferences, onOpenSystem: () -> Unit) {
    var baseUrl by remember { mutableStateOf(prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "") }
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var current by remember { mutableStateOf<Session?>(null) }
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sseStatus by remember { mutableStateOf("未连接") }
    var busy by remember { mutableStateOf(false) }
    var boxVersion by remember { mutableIntStateOf(0) }
    var progressVersion by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }

    val outbox = remember { Outbox(clock = { System.currentTimeMillis() }) }
    val progress = remember { ProgressTracker(clock = { System.currentTimeMillis() }) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val api = remember(baseUrl, token) { MephistoApi(UrlConnectionTransport(), baseUrl, token) }
    val listState = rememberLazyListState()

    fun persist() = prefs.edit().putString(KEY_BASE, baseUrl).putString(KEY_TOKEN, token).apply()

    fun loadSessions() {
        if (busy) return
        busy = true
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
                if (result is ApiResult.Ok && current?.id == sessionId) messages = result.value
            }
        }
    }

    fun openSession(session: Session) {
        current = session
        messages = emptyList()
        status = ""
        val client = api
        thread(name = "messages") {
            val result = client.messages(session.id)
            main.post {
                when (result) {
                    is ApiResult.Ok -> messages = result.value
                    is ApiResult.Failure -> status = result.readable
                }
            }
        }
    }

    fun createSession(title: String) {
        val name = title.trim().ifBlank { "新会话" }
        val client = api
        status = "创建中…"
        thread(name = "create") {
            val result = client.createSession(name)
            main.post {
                when (result) {
                    is ApiResult.Ok -> {
                        sessions = listOf(result.value) + sessions
                        openSession(result.value)
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

    // 200ms 一跳：后悔期倒计时与秒表都靠它
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    // 进 App 自动拉一次；令牌变化（比如刚填好）也重试
    LaunchedEffect(token) {
        if (token.isNotBlank()) loadSessions()
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

    // SSE：只在打开某个会话时连
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
                            // 只理会"正在跑的这一轮"。SSE 会重放历史事件，
                            // 旧 job 的 done/cancelled 会把进度行污染成"已打断"（真机上见过）。
                            if (progress.working() && progress.apply(event.data)) {
                                main.post { progressVersion++ }
                            }
                        }

                        "message" -> {
                            val incoming = runCatching { Parsers.messageEvent(event.data) }.getOrNull()
                            main.post {
                                if (incoming?.clientMsgId != null) {
                                    // 服务端回显了这条：本地"排队中"的条目可以收工了
                                    outbox.markDone(incoming.clientMsgId)
                                    boxVersion++
                                }
                                progressVersion++
                                reloadMessages(session.id)
                            }
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

    // 有新消息就滚到底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // 返回键：会话里退回列表；列表里交回系统（滑边缘不再直接掉桌面）
    BackHandler(enabled = current != null) {
        current = null
        messages = emptyList()
        progress.finish()
    }

    val progressLine = remember(progressVersion, now) { progress.statusLine(now) }

    if (current == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Mephisto", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        TextButton(onClick = onOpenSystem) { Text("提醒") }
                        TextButton(onClick = { showConnect = !showConnect }) { Text("连接") }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showNewDialog = true },
                    icon = { Text("＋") },
                    text = { Text("新会话") },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showConnect) {
                    ConnectionPanel(
                        baseUrl = baseUrl,
                        token = token,
                        onBaseUrl = { baseUrl = it },
                        onToken = { token = it },
                        onSave = {
                            persist()
                            loadSessions()
                        },
                    )
                }
                if (sessions.isEmpty()) {
                    EmptyHint("还没有会话。点右下角「新会话」开一个。")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sessions, key = { it.id }) { session ->
                            SessionRow(session) { openSession(session) }
                        }
                    }
                }
            }
        }
    } else {
        val session = current!!
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        TextButton(onClick = {
                            current = null
                            messages = emptyList()
                            progress.finish()
                        }) { Text("←") }
                    },
                    title = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        if (progress.working()) {
                            TextButton(onClick = {
                                val jobId = progress.jobId
                                val client = api
                                if (jobId != null) {
                                    thread(name = "interrupt") {
                                        val outcome = client.cancelJob(jobId)
                                        main.post {
                                            status = when (outcome) {
                                                is ApiResult.Ok -> "已打断"
                                                is ApiResult.Failure -> outcome.readable
                                            }
                                            progressVersion++
                                        }
                                    }
                                }
                            }) { Text("打断") }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                val crumbs = listOf(progressLine, status, "事件流：$sseStatus")
                    .filter { it.isNotBlank() && it != "空闲" }
                    .joinToString(" · ")
                if (crumbs.isNotBlank()) {
                    Text(
                        crumbs,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }

                val pending = remember(boxVersion) {
                    outbox.items().filter { it.sessionId == session.id && !it.terminal }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message -> Bubble(message) }
                    items(pending, key = { it.clientMsgId }) { item ->
                        PendingRow(
                            text = item.text,
                            badge = item.badge(now),
                            onUndo = { undo(item.clientMsgId) },
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("说点什么") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                    )
                    Button(onClick = { send() }, enabled = draft.isNotBlank()) { Text("发送") }
                }
                Text(
                    "发件箱 ${pending.size}/5 · 发送后 3 秒内可撤销",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
                )
            }
        }
    }

    if (showNewDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新会话") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会话名，例如「报销」") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewDialog = false
                    createSession(title)
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "seq ${session.seq}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bubble(message: Message) {
    val mine = !message.isOutgoing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    if (mine) "我" else "Mephisto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PendingRow(text: String, badge: String, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.widthIn(max = 240.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        TextButton(onClick = onUndo, modifier = Modifier.padding(start = 4.dp)) { Text("撤销") }
    }
}

@Composable
private fun ConnectionPanel(
    baseUrl: String,
    token: String,
    onBaseUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrl,
            label = { Text("服务端地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = onToken,
            label = { Text("Bearer 令牌") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("保存并刷新") }
            OutlinedButton(onClick = { onBaseUrl(DEFAULT_BASE) }) { Text("用本机地址") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
