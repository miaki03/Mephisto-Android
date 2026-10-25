package dev.mephisto.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 第 7 步的空壳：只有一个「检查 /healthz」按钮。
 *
 * 它的唯一职责是证明**这台机器能构建出能装到手机上的 APK，并且手机能打到服务端**。
 * 会话列表、SSE、闹钟分别在 S8 / S9 / S10 加。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HealthScreen()
            }
        }
    }
}

@Composable
private fun HealthScreen() {
    // 默认值只是占位：真机要填服务端的实际地址（隧道或局域网 IP）
    var baseUrl by remember { mutableStateOf("http://127.0.0.1:8787") }
    var status by remember { mutableStateOf("尚未检查") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Mephisto 客户端",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "第 7 步只验证一件事：这个 APK 能装上，并且能向服务端发出请求。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("服务端地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                status = "检查中…"
                val target = baseUrl.trim().trimEnd('/')
                thread(name = "healthz") {
                    val message = probeHealthz(target)
                    mainHandler.post { status = message }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("检查 /healthz")
        }

        Text(text = status, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 直接返回给界面的一行人话，不抛异常——失败原因要能看懂。 */
private fun probeHealthz(baseUrl: String): String {
    if (!baseUrl.startsWith("http")) {
        return "地址要以 http:// 或 https:// 开头"
    }
    return try {
        val connection = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        try {
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            "HTTP $code\n$body"
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        "失败：${error.javaClass.simpleName}：${error.message}"
    }
}
