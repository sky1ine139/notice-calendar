package com.noticecalendar.app.data

import org.json.JSONObject

/**
 * 一条通知事件记录（解析结果 + 原文 + 日历写入状态）
 */
data class EventRecord(
    val id: Long,
    val rawText: String,
    var title: String,
    var startMillis: Long,
    var endMillis: Long,
    var allDay: Boolean,
    var location: String,
    var description: String,
    var reminderMinutes: Int,   // -1 表示不提醒，其余为提前分钟数
    var calendarEventId: Long,  // 写入系统日历成功后的事件ID，-1 表示尚未写入
    var createdAt: Long,
    var source: String          // "AI解析" / "本地解析"
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("rawText", rawText)
        put("title", title)
        put("startMillis", startMillis)
        put("endMillis", endMillis)
        put("allDay", allDay)
        put("location", location)
        put("description", description)
        put("reminderMinutes", reminderMinutes)
        put("calendarEventId", calendarEventId)
        put("createdAt", createdAt)
        put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject): EventRecord {
            val now = System.currentTimeMillis()
            return EventRecord(
                id = o.optLong("id", now),
                rawText = o.optString("rawText"),
                title = o.optString("title", "未命名事件"),
                startMillis = o.optLong("startMillis", now),
                endMillis = o.optLong("endMillis", now + 3_600_000L),
                allDay = o.optBoolean("allDay", false),
                location = o.optString("location"),
                description = o.optString("description"),
                reminderMinutes = o.optInt("reminderMinutes", 30),
                calendarEventId = o.optLong("calendarEventId", -1L),
                createdAt = o.optLong("createdAt", now),
                source = o.optString("source")
            )
        }
    }
}
