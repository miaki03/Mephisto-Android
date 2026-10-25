package dev.mephisto.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mephisto.app.core.ApiResult
import dev.mephisto.app.core.KeepAlive
import dev.mephisto.app.core.KeepAliveLevel
import dev.mephisto.app.core.ReminderPlan
import dev.mephisto.app.data.Credentials
import dev.mephisto.app.service.KeepAliveService
import kotlin.concurrent.thread

/**
 * 开机 / 时区变更 / 时间被改 / App 更新后重排闹钟。
 *
 * `AlarmManager` 里的闹钟**不会**跨重启存活（`setAlarmClock` 也不保证），
 * 所以没有这个接收器，重启一次所有提醒就静默消失了。
 *
 * 广播只有几秒存活时间，所以用 `goAsync()` 撑着，网络与排程放到线程里做。
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!KeepAlive.shouldReschedule(intent.action)) return

        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "reschedule") {
            try {
                reschedule(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun reschedule(context: Context) {
        val credentials = Credentials.load(context)
        if (!credentials.usable) return

        val level = runCatching { KeepAliveLevel.valueOf(Credentials.level(context).uppercase()) }
            .getOrDefault(KeepAliveLevel.PERSISTENT)

        if (level != KeepAliveLevel.OFF) {
            KeepAliveService.start(context)
        }

        when (val result = credentials.api().notices()) {
            is ApiResult.Ok -> {
                val plans = ReminderPlan.plan(result.value, System.currentTimeMillis(), Alarms(context).canScheduleExact())
                val alarms = Alarms(context)
                for (plan in ReminderPlan.schedulable(plans)) {
                    alarms.schedule(plan)
                }
                for (plan in ReminderPlan.overdue(plans)) {
                    dev.mephisto.app.notify.Notifier.showAlarm(context, plan.noticeId, plan.title, "（延迟送达）")
                }
            }

            is ApiResult.Failure -> Unit  // 网络还没好：下次广播或打开 App 时会再排一次
        }
    }
}
