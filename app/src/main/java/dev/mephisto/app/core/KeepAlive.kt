package dev.mephisto.app.core

/**
 * 常驻与重排的判断。
 *
 * 这些判断放在纯逻辑里，是因为它们最容易"看起来能用、其实没生效"：
 * 服务起来了但没有通知权限 → 用户看不到、系统随时杀；
 * 开机广播收不到 → 重启后静默失去所有闹钟。
 */
enum class KeepAliveLevel(val label: String) {
    /** L0：只在打开 App 时同步。 */
    OFF("仅前台"),

    /** L1：常驻前台服务 + SSE（默认）。 */
    PERSISTENT("常驻"),

    /** L2：常驻 + 引导关闭电池优化。 */
    AGGRESSIVE("常驻+防杀"),
}

data class KeepAliveDecision(val startService: Boolean, val reason: String)

object KeepAlive {
    val RESCHEDULE_ACTIONS = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.MY_PACKAGE_REPLACED",
        "android.intent.action.TIME_SET",
        "android.intent.action.TIMEZONE_CHANGED",
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    fun shouldStart(
        level: KeepAliveLevel,
        notificationsAllowed: Boolean,
        batteryOptimized: Boolean,
    ): KeepAliveDecision = when {
        level == KeepAliveLevel.OFF ->
            KeepAliveDecision(false, "仅前台模式：不启动常驻服务")

        !notificationsAllowed ->
            // 仍然启动：通知被系统藏起来，但 SSE 还在跑；只是用户看不见
            KeepAliveDecision(true, "常驻已启动，但通知权限缺失——可能在后台被系统杀掉且你看不到")

        batteryOptimized && level == KeepAliveLevel.AGGRESSIVE ->
            KeepAliveDecision(true, "常驻已启动；建议关闭电池优化，否则仍可能被系统回收")

        batteryOptimized ->
            KeepAliveDecision(true, "常驻已启动（未关闭电池优化，系统可能随时回收）")

        else -> KeepAliveDecision(true, "常驻已启动")
    }

    /** 这些广播都意味着"闹钟可能失效了"，需要重新排。 */
    fun shouldReschedule(action: String?): Boolean = action != null && action in RESCHEDULE_ACTIONS
}
