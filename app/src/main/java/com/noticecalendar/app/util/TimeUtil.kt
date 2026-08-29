package com.noticecalendar.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

object TimeUtil {

    const val DAY_MS = 86_400_000L
    const val HOUR_MS = 3_600_000L

    /** 兼容 "2026-09-03" / "2026/9/3" / "2026年9月3日" / "2026.9.3" */
    fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        try {
            return LocalDate.parse(t)
        } catch (_: Exception) {
        }
        val m = Regex("(\\d{4})[-/年.](\\d{1,2})[-/月.](\\d{1,2})").find(t) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        return try {
            LocalDate.of(y, mo, d)
        } catch (_: Exception) {
            null
        }
    }

    /** 兼容 "19:00" / "7点" / "7点半" / "7点30分" */
    fun parseTime(s: String?): LocalTime? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        try {
            return LocalTime.parse(t)
        } catch (_: Exception) {
        }
        val m = Regex("(\\d{1,2})[点时:：]\\s*(半|(\\d{1,2})分?)?").find(t) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val minPart = m.groupValues[2]
        val min = when {
            minPart == "半" -> 30
            minPart.isEmpty() -> 0
            else -> m.groupValues[3].toIntOrNull() ?: 0
        }
        return try {
            LocalTime.of(h, min)
        } catch (_: Exception) {
            null
        }
    }

    /** 例如：2026-09-03 星期四 19:00 / 2026-09-03 星期四 全天 */
    fun formatMillis(millis: Long, allDay: Boolean): String {
        val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val week = dt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        return if (allDay) {
            String.format("%04d-%02d-%02d %s 全天", dt.year, dt.monthValue, dt.dayOfMonth, week)
        } else {
            String.format(
                "%04d-%02d-%02d %s %02d:%02d",
                dt.year, dt.monthValue, dt.dayOfMonth, week, dt.hour, dt.minute
            )
        }
    }
}
