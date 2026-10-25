package dev.mephisto.app.core

import java.time.Instant
import java.time.OffsetDateTime

/**
 * 把 `notice` 翻译成"该什么时候闹"。
 *
 * 为什么这部分要做成纯逻辑：闹钟一旦排错，表现是**静默不响**——用户只会觉得
 * "它没提醒我"，而你没有任何报错可看。所以排程规则必须能在 JVM 单测里钉死。
 */
enum class PlanKind {
    /** 未来某个时刻：交给 AlarmManager。 */
    FUTURE,

    /** 已经过了但还在宽限期内：立刻通知，不排闹钟。 */
    OVERDUE,

    /** 不处理：非 pending，或过期太久（服务端已经汇总成"你错过了 N 条"）。 */
    SKIP,
}

data class AlarmPlan(
    val noticeId: String,
    val title: String,
    val kind: PlanKind,
    /** FUTURE 时是闹钟时刻；OVERDUE 时是"现在"；SKIP 时为 0。 */
    val triggerAtMillis: Long,
    /** 由 notice.id 派生：同一条通知永远同一个值，重排不会产生两个闹钟。 */
    val requestCode: Int,
    /** false 表示这台机器不允许精确闹钟，调用方要退回 setWindow。 */
    val exact: Boolean,
)

object ReminderPlan {
    /** 超过这个时长的过期通知不再单独提醒。与调度器的宽限期保持一致。 */
    const val OVERDUE_GRACE_MILLIS = 2 * 60 * 60 * 1000L

    /**
     * 由 `notice.id` 派生 `requestCode`。
     *
     * 用 FNV-1a：稳定、无依赖、分布够均匀。关键是**稳定**——`AlarmManager` 的
     * 取消只认 `requestCode + Intent` 的匹配，值变了就取消不掉旧闹钟。
     */
    fun requestCode(noticeId: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (byte in noticeId.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        // 保证非负：负数在 PendingIntent 里也能用，但日志里容易看错
        return hash and 0x7FFFFFFF
    }

    /** ISO-8601（带偏移）→ 毫秒。解析失败返回 null，不抛。 */
    fun parseMillis(iso: String): Long? = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (failure: Exception) {
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (again: Exception) {
            null
        }
    }

    fun plan(
        notices: List<Notice>,
        nowMillis: Long,
        exactAllowed: Boolean,
        graceMillis: Long = OVERDUE_GRACE_MILLIS,
    ): List<AlarmPlan> {
        val seen = HashSet<String>()
        val plans = ArrayList<AlarmPlan>(notices.size)
        for (notice in notices) {
            if (notice.state != "pending") continue
            if (!seen.add(notice.id)) continue          // 同一 id 出现两次只排一次
            val due = parseMillis(notice.dueUtc)
            val code = requestCode(notice.id)
            if (due == null) {
                plans.add(AlarmPlan(notice.id, notice.title, PlanKind.SKIP, 0, code, exactAllowed))
                continue
            }
            val delta = due - nowMillis
            when {
                delta > 0L -> plans.add(
                    AlarmPlan(notice.id, notice.title, PlanKind.FUTURE, due, code, exactAllowed),
                )

                -delta <= graceMillis -> plans.add(
                    AlarmPlan(notice.id, notice.title, PlanKind.OVERDUE, nowMillis, code, exactAllowed),
                )

                else -> plans.add(AlarmPlan(notice.id, notice.title, PlanKind.SKIP, 0, code, exactAllowed))
            }
        }
        return plans
    }

    /** 只留下要真正排闹钟的，按时间排序（先响的在前）。 */
    fun schedulable(plans: List<AlarmPlan>): List<AlarmPlan> =
        plans.filter { it.kind == PlanKind.FUTURE }.sortedBy { it.triggerAtMillis }

    /** 需要立刻弹通知的（过期但还在宽限期）。 */
    fun overdue(plans: List<AlarmPlan>): List<AlarmPlan> = plans.filter { it.kind == PlanKind.OVERDUE }
}
