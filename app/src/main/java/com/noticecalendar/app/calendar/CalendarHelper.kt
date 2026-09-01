package com.noticecalendar.app.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.noticecalendar.app.data.EventRecord
import java.io.IOException
import java.util.TimeZone

/**
 * 通过 CalendarProvider 直接写入系统日历（需 READ/WRITE_CALENDAR 运行时权限），
 * 并按记录中的 reminderMinutes 添加弹窗提醒。
 */
object CalendarHelper {

    /** 系统里的一个日历账户（决定日程"类型"显示在哪个账户下） */
    data class CalAccount(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val isPrimary: Boolean
    )

    /** 列出所有可写入的日历账户 */
    fun listCalendars(context: Context): List<CalAccount> {
        val list = mutableListOf<CalAccount>()
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.IS_PRIMARY
            ),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
            arrayOf("${Calendars.CAL_ACCESS_CONTRIBUTOR}"),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    CalAccount(
                        id = c.getLong(0),
                        displayName = c.getString(1) ?: "",
                        accountName = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        isPrimary = c.getInt(4) == 1
                    )
                )
            }
        }
        return list
    }

    /** 智能选择：手机本地日历优先，Google/Exchange等同步账户靠后 */
    fun pickDefault(candidates: List<CalAccount>): CalAccount? =
        candidates.maxByOrNull { score(it) }

    private fun score(a: CalAccount): Int {
        var s = 0
        val type = a.accountType.lowercase()
        when {
            type == "local" -> s += 100
            "local" in type -> s += 80
        }
        if ("google" in type || "gmail" in a.accountName.lowercase()) s -= 40
        if (a.isPrimary) s += 10
        return s
    }

    /** 用户在设置中指定的账户仍有效则用之，否则智能选择 */
    fun resolveCalendarId(context: Context, preferredCalendarId: Long): Long? {
        val list = listCalendars(context)
        if (list.isEmpty()) return null
        if (preferredCalendarId > 0) {
            list.firstOrNull { it.id == preferredCalendarId }?.let { return it.id }
        }
        return pickDefault(list)?.id
    }

    fun label(a: CalAccount): String =
        if (a.displayName == a.accountName || a.accountName.isBlank()) a.displayName
        else "${a.displayName}（${a.accountName}）"

    @Throws(IOException::class)
    fun insertEvent(context: Context, record: EventRecord, preferredCalendarId: Long = 0L): Long {
        val calendarId = resolveCalendarId(context, preferredCalendarId)
            ?: throw IOException("未找到可写入的系统日历账户，请先打开系统「日历」App确认存在至少一个日历账户")

        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, record.title)
            put(Events.DESCRIPTION, record.description)
            put(Events.EVENT_LOCATION, record.location)
            put(Events.DTSTART, record.startMillis)
            if (record.allDay) {
                put(Events.ALL_DAY, 1)
                put(Events.DTEND, record.startMillis + 86_400_000L)
            } else {
                put(
                    Events.DTEND,
                    if (record.endMillis > record.startMillis) record.endMillis else record.startMillis + 3_600_000L
                )
            }
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
            ?: throw IOException("写入日历失败（系统返回空）")
        val eventId = ContentUris.parseId(uri)

        if (record.reminderMinutes >= 0) {
            val reminder = ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, record.reminderMinutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            runCatching { context.contentResolver.insert(Reminders.CONTENT_URI, reminder) }
        }
        return eventId
    }

    /** 删除系统日历中的事件（更新日程时先删旧的再建新的） */
    fun deleteEvent(context: Context, eventId: Long): Boolean {
        if (eventId <= 0) return false
        return try {
            val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
}
