package dev.mephisto.app.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 时间怎么显示给人看。
 *
 * 单独抽出来是因为它是纯字符串逻辑，能单测；也因为"昨天/刚刚"这类相对时间
 * 一旦写错，用户看到的是"3 天前"其实刚发——比不显示更糟。
 */
object TimeText {
    private val LOCAL: ZoneId = ZoneId.systemDefault()

    fun parseMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (failure: Exception) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (again: Exception) {
                null
            }
        }
    }

    /** 列表里的相对时间；解析失败返回 null，让调用方干脆不显示。 */
    fun relative(nowMillis: Long, iso: String?): String? {
        val at = parseMillis(iso) ?: return null
        val deltaSeconds = (nowMillis - at) / 1000
        if (deltaSeconds < 0) return clock(at)               // 时钟漂移：别显示"负几分钟前"
        if (deltaSeconds < 60) return "刚刚"
        if (deltaSeconds < 3600) return "${deltaSeconds / 60} 分钟前"

        val now = Instant.ofEpochMilli(nowMillis).atZone(LOCAL)
        val then = Instant.ofEpochMilli(at).atZone(LOCAL)
        return when {
            now.toLocalDate() == then.toLocalDate() -> clock(at)
            now.toLocalDate().minusDays(1) == then.toLocalDate() -> "昨天"
            now.year == then.year -> "${then.monthValue} 月 ${then.dayOfMonth} 日"
            else -> "${then.year} 年 ${then.monthValue} 月 ${then.dayOfMonth} 日"
        }
    }

    /** HH:mm，用于时间分隔与当天时间。 */
    fun clock(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(LOCAL)
        return String.format("%02d:%02d", at.hour, at.minute)
    }

    /** 对话里隔了多久就插一条居中的时间。 */
    fun showSeparator(previousIso: String?, currentIso: String?, gapMillis: Long = DEFAULT_GAP_MILLIS): Boolean {
        if (previousIso == null) return true
        val previous = parseMillis(previousIso) ?: return true
        val current = parseMillis(currentIso) ?: return false
        return current - previous >= gapMillis
    }

    fun separatorLabel(iso: String?): String {
        val at = parseMillis(iso) ?: return ""
        val then = Instant.ofEpochMilli(at).atZone(LOCAL)
        val today = Instant.now().atZone(LOCAL).toLocalDate()
        val day = when (then.toLocalDate()) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${then.monthValue} 月 ${then.dayOfMonth} 日"
        }
        return "$day ${clock(at)}"
    }

    /** 10 分钟：比这更短的间隔插时间条只会把对话切碎。 */
    const val DEFAULT_GAP_MILLIS = 10 * 60 * 1000L

    /** 服务端 `last_active_at` 这类可能缺失的字段，缺失就不显示，不猜。 */
    fun daysAgo(nowMillis: Long, iso: String?): Long? {
        val at = parseMillis(iso) ?: return null
        return ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(at).atZone(LOCAL).toLocalDate(),
            Instant.ofEpochMilli(nowMillis).atZone(LOCAL).toLocalDate(),
        )
    }
}
