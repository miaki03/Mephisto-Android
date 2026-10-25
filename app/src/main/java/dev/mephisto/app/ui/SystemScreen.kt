package dev.mephisto.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mephisto.app.alarm.Alarms
import dev.mephisto.app.core.ApiResult
import dev.mephisto.app.core.KeepAlive
import dev.mephisto.app.core.KeepAliveLevel
import dev.mephisto.app.core.Notice
import dev.mephisto.app.core.ReminderPlan
import dev.mephisto.app.data.Credentials
import dev.mephisto.app.notify.Notifier
import dev.mephisto.app.service.KeepAliveService
import kotlin.concurrent.thread

/**
 * 提醒与权限：这一屏存在的唯一理由是——**Android 不会替你把这些开关打开**。
 *
 * 精确闹钟、通知、电池优化，少开任何一个，表现都是"它没提醒我"，
 * 而你不会看到任何报错。所以把三件事做成一眼可见、一键可跳的按钮。
 */
@Composable
fun SystemScreen(context: Context, onBack: () -> Unit) {
    var status by remember { mutableStateOf("") }
    var notices by remember { mutableStateOf(emptyList<Notice>()) }
    var level by remember { mutableStateOf(KeepAliveLevel.PERSISTENT) }
    var batteryOptimized by remember { mutableStateOf(true) }
    val alarms = remember { Alarms(context) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> status = if (granted) "通知权限已授予" else "通知权限被拒绝——到点也不会有提醒" }

    fun refreshBattery() {
        val power = context.getSystemService(PowerManager::class.java)
        batteryOptimized = power?.isIgnoringBatteryOptimizations(context.packageName) != true
    }

    fun loadNotices() {
        val credentials = Credentials.load(context)
        if (!credentials.usable) {
            status = "先填服务端地址与令牌"
            return
        }
        status = "读取提醒…"
        thread(name = "notices") {
            val result = credentials.api().notices()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when (result) {
                    is ApiResult.Ok -> {
                        notices = result.value
                        status = "共 ${result.value.size} 条待提醒"
                    }

                    is ApiResult.Failure -> status = result.readable
                }
            }
        }
    }

    fun schedulePlans() {
        val plans = ReminderPlan.plan(notices, System.currentTimeMillis(), alarms.canScheduleExact())
        val futures = ReminderPlan.schedulable(plans)
        var exactCount = 0
        for (plan in futures) {
            if (alarms.schedule(plan)) exactCount += 1
        }
        for (plan in ReminderPlan.overdue(plans)) {
            Notifier.showAlarm(context, plan.noticeId, plan.title, "（已过点，延迟送达）")
        }
        status = "已排 ${futures.size} 个闹钟（其中精确 $exactCount 个）；" +
            if (exactCount < futures.size) "其余是 10 分钟窗口，建议打开精确闹钟权限" else "全部精确"
    }

    refreshBattery()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("提醒与权限", style = MaterialTheme.typography.titleLarge)
        Text(
            "少开任何一个开关，表现都是「它没提醒我」，而且没有报错。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    status = "这个版本不需要单独申请通知权限"
                }
            }) { Text("通知权限") }

            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    }.onFailure { status = "这台机器没有精确闹钟设置页" }
                } else {
                    status = "Android 12 以下默认精确"
                }
            }) { Text("精确闹钟") }

            OutlinedButton(onClick = {
                if (batteryOptimized) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }.onFailure { status = "打不开电池优化设置" }
                } else {
                    status = "已经在忽略电池优化"
                }
            }) { Text(if (batteryOptimized) "关闭电池优化" else "电池优化已关") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val decision = KeepAlive.shouldStart(
                level = level,
                notificationsAllowed = true,
                batteryOptimized = batteryOptimized,
            )
            Button(onClick = {
                if (level == KeepAliveLevel.OFF) {
                    level = KeepAliveLevel.PERSISTENT
                    Credentials.setLevel(context, "persistent")
                    KeepAliveService.start(context)
                    status = "常驻已开启"
                } else {
                    level = KeepAliveLevel.OFF
                    Credentials.setLevel(context, "off")
                    KeepAliveService.stop(context)
                    status = "常驻已关闭（只在前台同步）"
                }
            }) { Text(if (level == KeepAliveLevel.OFF) "开启常驻" else "关闭常驻") }
            Text(decision.reason, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { loadNotices() }) { Text("刷新提醒") }
            Button(onClick = { schedulePlans() }, enabled = notices.isNotEmpty()) { Text("全部排成闹钟") }
        }

        if (notices.isEmpty()) {
            Text("还没有待提醒。可以让 agent 用 remind_add 建一个。", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(notices, key = { it.id }) { notice ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                            Text(notice.title, style = MaterialTheme.typography.bodyMedium)
                            Text(notice.dueUtc, style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = {
                            val plans = ReminderPlan.plan(
                                listOf(notice),
                                System.currentTimeMillis(),
                                alarms.canScheduleExact(),
                            )
                            for (plan in ReminderPlan.schedulable(plans)) {
                                val exact = alarms.schedule(plan)
                                status = if (exact) "已排精确闹钟" else "已排窗口闹钟（建议开精确权限）"
                            }
                            for (plan in ReminderPlan.overdue(plans)) {
                                Notifier.showAlarm(context, plan.noticeId, plan.title, "（已过点，延迟送达）")
                                status = "这条已经过点，直接弹了通知"
                            }
                        }) { Text("排闹钟") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
