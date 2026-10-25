package dev.mephisto.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.mephisto.app.core.SseClient
import dev.mephisto.app.data.Credentials
import dev.mephisto.app.notify.Notifier
import org.json.JSONObject

/**
 * 常驻前台服务：维持一条 SSE，把 `notice` 事件变成通知。
 *
 * 为什么用 `remoteMessaging` 类型：它**没有 6 小时上限**（那个上限只针对 `dataSync` /
 * `mediaProcessing`）。常驻通知走 `IMPORTANCE_LOW`，无声、不弹。
 *
 * 注意它只负责"在线时立刻收到"；"到点必须响"靠 `setAlarmClock`——两者互补：
 * 前者依赖 PC 开机 + 隧道通，后者只依赖手机自己。
 */
class KeepAliveService : Service() {

    private var client: SseClient? = null

    override fun onCreate() {
        super.onCreate()
        val notification = Notifier.serviceNotification(this, "正在等待提醒")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifier.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(Notifier.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val credentials = Credentials.load(this)
        if (!credentials.usable) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (client == null) {
            client = SseClient(
                baseUrl = credentials.baseUrl,
                token = credentials.token,
                onEvent = ::handleEvent,
                onStatus = { text -> updateNotification(text) },
            ).also { it.start(0) }
        }
        return START_STICKY
    }

    private fun handleEvent(event: dev.mephisto.app.core.SseEvent) {
        if (event.kind != "notice") return
        try {
            val payload = JSONObject(event.data)
            val notice = payload.optJSONObject("notice") ?: payload
            val id = notice.optString("id").ifEmpty { "unknown" }
            val title = notice.optString("title").ifEmpty { "提醒" }
            Notifier.showAlarm(this, id, title, notice.optString("body").ifEmpty { null })
        } catch (_: Exception) {
            // 事件形状不认识就忽略：宁可少一条通知，也不要让服务崩掉
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(Notifier.SERVICE_NOTIFICATION_ID, Notifier.serviceNotification(this, text))
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
