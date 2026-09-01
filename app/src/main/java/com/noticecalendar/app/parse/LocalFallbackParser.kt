package com.noticecalendar.app.parse

import com.noticecalendar.app.llm.ParsedEvent
import java.time.LocalDate
import java.time.LocalTime

/**
 * 无网络 / 未配置API Key / API调用失败时的本地规则兜底解析。
 * 精度有限，解析结果会标注为"本地解析"，提示用户手动校正。
 */
object LocalFallbackParser {

    private val EMOJI = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\uFE0F\\u200D\\u2600-\\u27BF#*\\u20E3]+")
    private val TIME_RE = Regex("(凌晨|早上|上午|中午|下午|晚上|晚)?\\s*(\\d{1,2})[点时:：]\\s*(半|(\\d{1,2})分?)?")
    private val UPDATE_WORDS = Regex("延期|改期|更改|调整|推迟|顺延|变更|改到|改为|改在|时间改|地点改")

    fun parse(raw: String): ParsedEvent {
        val text = raw.replace("\n", " ")
        val today = LocalDate.now()
        val isUpdate = UPDATE_WORDS.containsMatchIn(raw)

        // 先找时间，再从剩余文本中找日期，避免 "2026.9.3" 之类被误当时间
        var time: LocalTime? = null
        var matchedTimeRange: IntRange? = null
        val tm = TIME_RE.find(text)
        if (tm != null) {
            val hourRaw = tm.groupValues[2].toIntOrNull() ?: -1
            val period = tm.groupValues[1]
            val minPart = tm.groupValues[3]
            val minute = when {
                minPart == "半" -> 30
                minPart.isEmpty() -> 0
                else -> minPart.removeSuffix("分").toIntOrNull() ?: 0
            }
            var hour = hourRaw
            if (period == "下午" || period == "晚上" || period == "晚") {
                if (hour in 1..11) hour += 12
            } else if (period == "中午") {
                hour = 12
            }
            if (hour in 0..23 && minute in 0..59) {
                time = LocalTime.of(hour, minute)
                matchedTimeRange = tm.range
            }
        }
        val date = findDate(if (matchedTimeRange != null) text.replaceRange(matchedTimeRange, " ") else text, today)

        // 只有时段没有钟点（如"9月11日晚进行"）：映射默认时刻，避免误判为全天
        if (time == null) {
            time = vaguePeriodTime(text)
        }

        val title = firstTitle(raw)
        return ParsedEvent(
            title = title,
            date = date?.toString(),
            time = time?.toString(),
            endTime = null,
            allDay = time == null,
            location = findLocation(text),
            description = "",
            type = if (isUpdate && date != null) "update" else "new",
            matchKeyword = if (isUpdate) extractKeyword(title) else null
        )
    }

    /** 从标题中提取匹配关键词：去掉"通知""通知】"等后缀，取核心事件名 */
    private fun extractKeyword(title: String?): String? {
        if (title.isNullOrBlank()) return null
        var k = title
            .removePrefix("【").removeSuffix("】")
            .removeSuffix("通知").removeSuffix("通知】")
            .removeSuffix("的通知").trim()
        if (k.length > 12) k = k.take(12)
        return k.ifBlank { null }
    }

    private fun findDate(text: String, today: LocalDate): LocalDate? {
        if (Regex("大后天").containsMatchIn(text)) return today.plusDays(3)
        if (Regex("后天").containsMatchIn(text)) return today.plusDays(2)
        if (Regex("明天|明日").containsMatchIn(text)) return today.plusDays(1)
        if (Regex("今天|今日|今晚|今早").containsMatchIn(text)) return today

        // 下下周X / 下周X
        val mNext = Regex("(下下|下)(?:周|星期|礼拜)([一二三四五六日天])").find(text)
        if (mNext != null) {
            val target = cnWeekday(mNext.groupValues[2])
            if (target > 0) {
                val weeksAhead = if (mNext.groupValues[1] == "下下") 2 else 1
                val dow = today.dayOfWeek.value // 周一=1 … 周日=7
                return today.plusDays(((8 - dow) + (target - 1) + 7L * (weeksAhead - 1)))
            }
        }
        // 本周X / 周X / 星期X（取最近的一天，含今天）
        val mThis = Regex("(?:周|星期|礼拜)([一二三四五六日天])").find(text)
        if (mThis != null) {
            val target = cnWeekday(mThis.groupValues[1])
            if (target > 0) {
                val dow = today.dayOfWeek.value
                return today.plusDays(((target - dow + 7) % 7).toLong())
            }
        }
        // 完整日期 2026-09-03 / 2026/9/3 / 2026.9.3
        val mFull = Regex("(\\d{4})[-/年.](\\d{1,2})[-/月.](\\d{1,2})").find(text)
        if (mFull != null) {
            val d = buildDate(mFull.groupValues[1].toIntOrNull(), mFull.groupValues[2].toIntOrNull(), mFull.groupValues[3].toIntOrNull(), today)
            if (d != null) return d
        }
        // 9月10日 / 9月10号
        val mMd = Regex("(\\d{1,2})月(\\d{1,2})[日号]").find(text)
        if (mMd != null) {
            val d = buildDate(today.year, mMd.groupValues[1].toIntOrNull(), mMd.groupValues[2].toIntOrNull(), today)
            if (d != null) return d
        }
        return null
    }

    private fun buildDate(y: Int?, mo: Int?, d: Int?, today: LocalDate): LocalDate? {
        if (y == null || mo == null || d == null) return null
        if (mo !in 1..12 || d !in 1..31) return null
        return try {
            var candidate = LocalDate.of(y, mo, d)
            // 只给"9月10日"没写年份时，若已过去超过半年则按明年理解
            if (candidate.isBefore(today.minusDays(180))) candidate = candidate.plusYears(1)
            candidate
        } catch (_: Exception) {
            null
        }
    }

    /** "今晚/晚上/下午"等只有时段没有钟点时的兜底映射 */
    private fun vaguePeriodTime(text: String): LocalTime? = when {
        Regex("晚上|傍晚|晚间|今晚|今夜|夜里|深夜|晚").containsMatchIn(text) -> LocalTime.of(19, 0)
        Regex("中午|午间").containsMatchIn(text) -> LocalTime.of(12, 0)
        Regex("下午").containsMatchIn(text) -> LocalTime.of(14, 0)
        Regex("上午|早上|清晨|凌晨").containsMatchIn(text) -> LocalTime.of(9, 0)
        else -> null
    }

    private fun cnWeekday(c: String): Int = when (c) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> 0
    }

    private fun firstTitle(raw: String): String? {
        val line = raw.lines().map { it.trim() }.firstOrNull { it.length >= 2 } ?: return null
        var t = EMOJI.replace(line, "")
        t = t.replace(Regex("^[\\s@，。！!？?：:、～\\-]+"), "").trim()
        t = t.removePrefix("通知：").removePrefix("通知:").trim()
        if (t.length > 20) t = t.take(20)
        return t.ifBlank { null }
    }

    private fun findLocation(text: String): String {
        val m1 = Regex("地点[:：]\\s*([^\\s，,。；;]+)").find(text)
        if (m1 != null) return m1.groupValues[1]
        val m2 = Regex(
            "(?:在|于)([\\u4e00-\\u9fa5A-Za-z0-9\\-]{1,15}?(?:教室|会议室|实验室|报告厅|操场|图书馆|体育馆|教学楼|宿舍|号楼|楼|栋|室|厅))"
        ).find(text)
        if (m2 != null) return m2.groupValues[1]
        return ""
    }
}
