package com.noticecalendar.app.data

import android.content.Context

/**
 * 大模型 API 配置（OpenAI 兼容接口）。密钥仅保存在本机 SharedPreferences，绝不硬编码。
 */
object SettingsStore {

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val preferredCalendarId: Long = 0L   // 0 = 自动（本地日历优先）
    ) {
        val hasKey: Boolean get() = apiKey.isNotBlank()
    }

    fun load(context: Context): Config {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Config(
            baseUrl = sp.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            apiKey = sp.getString(KEY_API_KEY, null) ?: "",
            model = sp.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            preferredCalendarId = sp.getLong(KEY_PREF_CALENDAR, 0L)
        )
    }

    /** 保存用户选择的日历账户（0 = 自动） */
    fun saveCalendarPref(context: Context, calendarId: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_PREF_CALENDAR, calendarId)
            .apply()
    }

    fun save(context: Context, baseUrl: String, apiKey: String, model: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }

    private const val PREF = "settings"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_PREF_CALENDAR = "preferred_calendar_id"
}
