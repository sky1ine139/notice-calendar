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
            .putString(KEY_BASE_URL, normalizeBaseUrl(baseUrl))
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, normalizeModel(model))
            .apply()
    }

    /**
     * 规范化接口地址，容忍常见填错：
     * - 贴了控制台/官网地址（platform.deepseek.com、deepseek.com）→ 换成真正的 API 域名
     * - 漏写 /v1 或写了别的版本号 → 统一成 /v1
     * - 多写的 /chat/completions 后缀 → 去掉（客户端会自己拼）
     * - 前后空格、结尾多余的 /
     */
    fun normalizeBaseUrl(raw: String): String {
        var u = raw.trim()
        if (u.isBlank()) return DEFAULT_BASE_URL
        // 协议补全：只写域名时补 https://
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        // 客户端会自己拼 /chat/completions，用户多写要去掉
        u = u.removeSuffix("/chat/completions").removeSuffix("/completions")
        // 常见误填：把控制台/官网当成接口地址
        u = u.replace("https://platform.deepseek.com", "https://api.deepseek.com")
            .replace("https://www.deepseek.com", "https://api.deepseek.com")
            .replace("https://deepseek.com", "https://api.deepseek.com")
        u = u.trimEnd('/')
        // 没有版本号时补 /v1（别的服务商版本段同理）
        val hasVersion = Regex("/v\\d+[a-z]*$", RegexOption.IGNORE_CASE).containsMatchIn(u)
        if (!hasVersion) u = "$u/v1"
        return u
    }

    /** 规范化模型名：只写服务商名（如 "deepseek"）时补成可用的默认模型 */
    fun normalizeModel(raw: String): String {
        val m = raw.trim()
        if (m.isBlank()) return DEFAULT_MODEL
        return when (m.lowercase()) {
            "deepseek", "deepseek-v3", "deepseek-v2" -> "deepseek-chat"
            "glm", "zhipu", "智谱" -> "glm-4-flash"
            "kimi", "moonshot" -> "moonshot-v1-8k"
            "qwen", "通义千问" -> "qwen-plus"
            "openai", "gpt" -> "gpt-4o-mini"
            else -> m
        }
    }

    private const val PREF = "settings"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_PREF_CALENDAR = "preferred_calendar_id"
}
