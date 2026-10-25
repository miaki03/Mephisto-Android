package dev.mephisto.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.mephisto.app.core.AlarmPlan

/**
 * `AlarmManager` 的薄包装。
 *
 * 三个关键点：
 * 1. **`requestCode` 与 `data` 一起决定 PendingIntent 的身份**——两者都由 `notice.id` 派生，
 *    所以重排是覆盖而不是叠加，取消能真的取消掉。
 * 2. 有精确闹钟权限用 `setAlarmClock`（系统会显示闹钟图标、不受 Doze 限制）；
 *    没有就退 `setWindow`（10 分钟窗口），并让界面提示用户去开权限。
 * 3. `setAlarmClock` 必须给 `showIntent`：用户点状态栏闹钟图标要能回到 App。
 */
class Alarms(private val context: Context) {

    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    /** 返回实际用的是精确还是窗口（界面要据此提示）。 */
    fun schedule(plan: AlarmPlan): Boolean {
        val alarmManager = manager ?: return false
        val pending = pendingIntent(plan.noticeId, plan.title)
        return if (plan.exact && canScheduleExact()) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(plan.triggerAtMillis, showIntent()),
                pending,
            )
            true
        } else {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                plan.triggerAtMillis,
                WINDOW_MILLIS,
                pending,
            )
            false
        }
    }

    fun cancel(noticeId: String) {
        manager?.cancel(pendingIntent(noticeId, ""))
    }

    private fun pendingIntent(noticeId: String, title: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            // data 让每条通知的 Intent 身份唯一；只靠 requestCode 在跨 id 时容易撞
            data = Uri.parse("mephisto://notice/$noticeId")
            putExtra(EXTRA_NOTICE_ID, noticeId)
            putExtra(EXTRA_TITLE, title)
        }
        return PendingIntent.getBroadcast(
            context,
            dev.mephisto.app.core.ReminderPlan.requestCode(noticeId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, dev.mephisto.app.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_ALARM = "dev.mephisto.app.action.ALARM"
        const val EXTRA_NOTICE_ID = "notice_id"
        const val EXTRA_TITLE = "title"
        private const val WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
