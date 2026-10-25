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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mephisto.app.core.ApiResult
import dev.mephisto.app.core.ChatStore
import dev.mephisto.app.core.Message
import dev.mephisto.app.core.MephistoApi
import dev.mephisto.app.core.Outbox
import dev.mephisto.app.core.OutboxState
import dev.mephisto.app.core.Parsers
import dev.mephisto.app.core.ProgressTracker
import dev.mephisto.app.core.QueueFullException
import dev.mephisto.app.core.Session
import dev.mephisto.app.core.SseClient
import dev.mephisto.app.core.TimeText
import dev.mephisto.app.core.UndoPlan
import dev.mephisto.app.core.UrlConnectionTransport
import dev.mephisto.app.ui.Avatar
import dev.mephisto.app.ui.Banner
import dev.mephisto.app.ui.EmptyState
import dev.mephisto.app.ui.SkeletonRow
import dev.mephisto.app.ui.StatusStrip
import dev.mephisto.app.ui.SystemScreen
import dev.mephisto.app.ui.TimeSeparator
import dev.mephisto.app.ui.Tone
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

private const val PREFS = "mephisto"
private const val KEY_BASE = "base_url"
private const val KEY_TOKEN = "token"
private const val DEFAULT_BASE = "http://127.0.0.1:8787"

private fun newClientMsgId(): String =
    "c_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Root(prefs)
            }
        }
    }
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
    var currentId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var sseStatus by remember { mutableStateOf("未连接") }
    var loadingSessions by remember { mutableStateOf(false) }
    var boxVersion by remember { mutableIntStateOf(0) }
    var progressVersion by remember { mutableIntStateOf(0) }
    var storeVersion by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(token.isBlank()) }

    val store = remember { ChatStore() }
    val outbox = remember { Outbox() }
    val progress = remember { ProgressTracker(clock = { System.currentTimeMillis() }) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val api = remember(baseUrl, token) { MephistoApi(UrlConnectionTransport(), baseUrl, token) }
    val listState = rememberLazyListState()

    val current = sessions.firstOrNull { it.id == currentId }
    val messages = remember(storeVersion, currentId) { store.messages(currentId ?: "") }

    fun persist() = prefs.edit().putString(KEY_BASE, baseUrl).putString(KEY_TOKEN, token).apply()

    fun loadSessions() {
        loadingSessions = true
        failure = ""
        persist()
        val client = api
        thread(name = "sessions") {
            val result = client.sessions()
            main.post {
                loadingSessions = false
                when (result) {
                    is ApiResult.Ok -> sessions = result.value
                    is ApiResult.Failure -> failure = result.readable
                }
            }
        }
    }

    /** 有缓存就不打网络：这是"进会话不再卡"的关键。 */
    fun ensureMessages(sessionId: String) {
        if (store.has(sessionId) && !store.stale(sessionId)) return
        store.touch(sessionId)
        val client = api
        thread(name = "messages") {
            val result = client.messages(sessionId)
            main.post {
                when (result) {
                    is ApiResult.Ok -> {
                        store.put(sessionId, result.value)
                        storeVersion++
                        failure = ""
                    }

                    is ApiResult.Failure -> failure = result.readable
                }
            }
        }
    }

    fun openSession(session: Session) {
        currentId = session.id
        failure = ""
        ensureMessages(session.id)
    }

    fun createSession(title: String) {
        val name = title.trim().ifBlank { "新会话" }
        val client = api
        thread(name = "create") {
            val result = client.createSession(name)
            main.post {
                when (result) {
                    is ApiResult.Ok -> {
                        sessions = listOf(result.value) + sessions
                        store.put(result.value.id, emptyList())
                        storeVersion++
                        openSession(result.value)
                    }

                    is ApiResult.Failure -> failure = result.readable
                }
            }
        }
    }

    /** 立即发送。没有 3 秒后悔期了——按下就是发出去。 */
    fun send() {
        val session = current ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        val item = try {
            outbox.enqueue(session.id, text, ::newClientMsgId)
        } catch (full: QueueFullException) {
            failure = "已排 ${full.depth} 条，等这轮跑完再发"
            return
        }
        draft = ""
        boxVersion++
        val client = api
        thread(name = "send") {
            val result = client.postMessage(session.id, text, item.clientMsgId)
            main.post {
                when (result) {
                    is ApiResult.Ok -> {
                        outbox.markQueued(item.clientMsgId, result.value.jobId)
                        if (currentId == session.id) progress.start(result.value.jobId)
                    }

                    is ApiResult.Failure -> {
                        outbox.markFailed(item.clientMsgId, result.readable)
                        failure = result.readable
                    }
                }
                boxVersion++
                progressVersion++
            }
        }
    }

    fun cancelItem(clientMsgId: String) {
        when (val plan = outbox.cancel(clientMsgId)) {
            is UndoPlan.CancelJob -> {
                val jobId = plan.jobId
                val client = api
                if (jobId == null) {
                    outbox.markCancelled(clientMsgId)
                    boxVersion++
                    return
                }
                thread(name = "cancel") {
                    val outcome = client.cancelJob(jobId)
                    main.post {
                        when (outcome) {
                            is ApiResult.Ok -> outbox.markCancelled(clientMsgId)
                            is ApiResult.Failure -> {
                                outbox.markFailed(clientMsgId, outcome.readable)
                                failure = outcome.readable
                            }
                        }
                        boxVersion++
                    }
                }
            }

            is UndoPlan.TooLate -> {
                failure = "已经结束了，来不及取消"
                boxVersion++
            }

            UndoPlan.NotFound -> Unit
        }
    }

    fun retryItem(clientMsgId: String) {
        val item = outbox.retry(clientMsgId) ?: return
        boxVersion++
        val client = api
        thread(name = "retry") {
            val result = client.postMessage(item.sessionId, item.text, item.clientMsgId)
            main.post {
                when (result) {
                    is ApiResult.Ok -> outbox.markQueued(item.clientMsgId, result.value.jobId)
                    is ApiResult.Failure -> {
                        outbox.markFailed(item.clientMsgId, result.readable)
                        failure = result.readable
                    }
                }
                boxVersion++
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    LaunchedEffect(token) {
        if (token.isNotBlank()) loadSessions()
    }

    // 全局一条 SSE：不随会话切换重连，也不再用它触发全量重拉
    DisposableEffect(baseUrl, token) {
        if (token.isBlank()) {
            onDispose { }
        } else {
            val client = SseClient(
                baseUrl = baseUrl,
                token = token,
                onEvent = { event ->
                    when (event.kind) {
                        "message" -> {
                            val incoming = runCatching { Parsers.messageEvent(event.data) }.getOrNull()
                            if (incoming != null) {
                                main.post {
                                    // 事件自带 session_id；退一步用当前会话，再退就不合并
                                    val target = incoming.sessionId.ifBlank { currentId.orEmpty() }
                                    if (target.isNotEmpty() && store.merge(target, incoming)) {
                                        storeVersion++
                                    }
                                    if (incoming.clientMsgId != null) {
                                        outbox.markDone(incoming.clientMsgId)
                                        boxVersion++
                                    }
                                }
                            }
                        }

                        "job" -> {
                            if (progress.working() && progress.apply(event.data)) {
                                main.post { progressVersion++ }
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

    // 只有在底部附近才自动滚：否则你翻历史时来一条消息就被拽回去
    LaunchedEffect(messages.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0 || last >= total - 3) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
    }

    BackHandler(enabled = currentId != null) {
        currentId = null
        progress.finish()
    }

    val progressLine = remember(progressVersion, now) { progress.statusLine(now) }

    if (currentId == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Mephisto", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    actions = {
                        TextButton(onClick = onOpenSystem) { Text("提醒") }
                        TextButton(onClick = { showConnect = !showConnect }) { Text("连接") }
                    },
                )
            },
            floatingActionButton = {
                if (token.isNotBlank()) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewDialog = true },
                        icon = { Text("＋") },
                        text = { Text("新会话") },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
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
                if (failure.isNotBlank()) {
                    Banner(failure, onRetry = { loadSessions() })
                }
                when {
                    loadingSessions && sessions.isEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(4) { SkeletonRow() }
                        }
                    }

                    sessions.isEmpty() -> EmptyState(
                        title = "还没有会话",
                        hint = if (token.isBlank()) "先在「连接」里填地址与令牌" else "点右下角「新会话」开一个",
                    )

                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                preview = store.messages(session.id).lastOrNull()?.text,
                                nowMillis = now,
                                onClick = { openSession(session) },
                            )
                        }
                    }
                }
            }
        }
    } else {
        val session = current
        val pending = remember(boxVersion) {
            outbox.items().filter {
                it.sessionId == currentId && it.state != OutboxState.DONE && it.state != OutboxState.CANCELLED
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        TextButton(onClick = {
                            currentId = null
                            progress.finish()
                        }) { Text("←") }
                    },
                    title = { Text(session?.title ?: "会话", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    actions = {
                        if (progress.working()) {
                            TextButton(onClick = {
                                val jobId = progress.jobId
                                val client = api
                                if (jobId != null) {
                                    thread(name = "interrupt") {
                                        val outcome = client.cancelJob(jobId)
                                        main.post {
                                            if (outcome is ApiResult.Failure) failure = outcome.readable
                                            progressVersion++
                                        }
                                    }
                                }
                            }) { Text("打断", color = MaterialTheme.colorScheme.error) }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                val strip = buildString {
                    if (progressLine != "空闲") append(progressLine)
                    if (sseStatus != "已连接" && sseStatus != "未连接") {
                        if (isNotEmpty()) append(" · ")
                        append(sseStatus)
                    }
                }
                if (strip.isNotBlank()) {
                    StatusStrip(
                        strip,
                        tone = when {
                            failure.isNotBlank() -> Tone.ERROR
                            progress.state == "failed" -> Tone.ERROR
                            sseStatus != "已连接" && sseStatus != "未连接" -> Tone.WARN
                            else -> Tone.NORMAL
                        },
                    )
                }
                if (failure.isNotBlank()) {
                    Banner(failure, onRetry = { currentId?.let { ensureMessages(it) } })
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        val index = messages.indexOf(message)
                        val previous = if (index > 0) messages[index - 1] else null
                        if (TimeText.showSeparator(previous?.createdAt, message.createdAt)) {
                            TimeSeparator(TimeText.separatorLabel(message.createdAt))
                        }
                        Bubble(message)
                    }
                    items(pending, key = { it.clientMsgId }) { item ->
                        PendingRow(
                            text = item.text,
                            badge = item.badge(),
                            failed = item.state == OutboxState.FAILED,
                            onCancel = { cancelItem(item.clientMsgId) },
                            onRetry = { retryItem(item.clientMsgId) },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("说点什么") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                    )
                    Button(
                        onClick = { send() },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Text("发送") }
                }
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
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(session: Session, preview: String?, nowMillis: Long, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(session.title)
            Column(
                Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TimeText.relative(nowMillis, session.lastActiveAt)?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    preview?.take(60) ?: "· ${session.seq} 轮对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Bubble(message: Message) {
    // direction == "out" 是 agent 的回复；这里只在 UI 层反着取，不动数据模型
    val mine = !message.isOutgoing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp,
            ),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
private fun PendingRow(
    text: String,
    badge: String,
    failed: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (failed) "重发" else "取消",
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { if (failed) onRetry() else onCancel() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        Modifier.fillMaxWidth().padding(12.dp),
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
