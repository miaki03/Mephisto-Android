package dev.mephisto.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mephisto.app.notify.Notifier

/** 闹钟到点：弹通知。真正"响"由系统的 setAlarmClock 负责，这里只负责把内容呈现出来。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Alarms.ACTION_ALARM) return
        val noticeId = intent.getStringExtra(Alarms.EXTRA_NOTICE_ID) ?: return
        val title = intent.getStringExtra(Alarms.EXTRA_TITLE).orEmpty().ifBlank { "提醒" }
        Notifier.showAlarm(context, noticeId, title, null)
    }
}
