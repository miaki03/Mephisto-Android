package dev.mephisto.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.mephisto.app.MainActivity

/**
 * 通知渠道。
 *
 * 两条渠道刻意分开：
 * - `alarm`：IMPORTANCE_HIGH，有声音、会亮屏——提醒就是要你注意到。
 * - `service`：IMPORTANCE_LOW，无声、不弹出——常驻通知不该打扰人。
 *
 * 合成一条渠道的结果是"常驻通知每 15 秒响一次"，或者"闹钟静音了"。
 */
object Notifier {
    const val CHANNEL_ALARM = "mephisto_alarm"
    const val CHANNEL_SERVICE = "mephisto_service"
    const val SERVICE_NOTIFICATION_ID = 1001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点的提醒与打断结果"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "常驻", NotificationManager.IMPORTANCE_LOW).apply {
                description = "连接状态，不打扰"
                setShowBadge(false)
            },
        )
    }

    fun showAlarm(context: Context, noticeId: String, title: String, body: String?) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = Notification.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body ?: "点开查看")
            .setStyle(Notification.BigTextStyle().bigText(body ?: title))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        // 用 notice.id 派生的稳定 id：同一条通知只会覆盖，不会堆一屏
        manager.notify(stableId(noticeId), notification)
    }

    fun serviceNotification(context: Context, text: String): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mephisto")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** `notice.id` 是 ULID，取低位稳定映射到通知 id。 */
    fun stableId(noticeId: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (byte in noticeId.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return (hash and 0x7FFFFFFF) % 900_000
    }
}
