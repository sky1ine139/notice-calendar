package com.noticecalendar.app.llm

import com.noticecalendar.app.data.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 大模型解析结果（尚未换算成毫秒时间戳）
 */
data class ParsedEvent(
    val title: String?,
    val date: String?,      // YYYY-MM-DD，null 表示通知里没有任何日期
    val time: String?,      // HH:mm，null 表示只有日期没有时间
    val endTime: String?,
    val allDay: Boolean,
    val location: String,   // 空字符串表示无地点
    val description: String,
    val type: String = "new",       // "new"=新建日程，"update"=更新已有日程（延期/改期等）
    val matchKeyword: String? = null // type=update 时，用于匹配历史日程的关键词（通常是事件名称）
)

/**
 * OpenAI 兼容 chat/completions 客户端。
 * 兼容 DeepSeek / 智谱GLM / Kimi / 通义千问(兼容模式) / OpenAI / 本地 Ollama 等。
 * API Key 由用户在设置页填入，存于本机，不在代码中出现。
 */
object LlmClient {

    private const val SYSTEM_PROMPT = """你是一个校园群通知的日程信息抽取助手。用户会给出一段微信群/QQ群里的通知原文，其中可能包含表情符号、问候语、闲聊、@全体成员、换行等干扰内容，你要过滤无关信息，只抽取日程要素。
只输出一个JSON对象，禁止输出任何解释、前后缀或Markdown代码块，字段定义如下：
{"title":"事件标题，20字以内","date":"YYYY-MM-DD 或 null","time":"HH:mm 或 null","end_time":"HH:mm 或 null","all_day":true或false,"location":"地点字符串，未提到则为空字符串","description":"关键补充信息（如需携带的材料、参加对象、注意事项），没有则为空字符串","type":"new或update","match_keyword":"type=update时填写用于匹配历史日程的事件名称关键词，否则为空字符串"}
抽取规则：
1. 用户消息里会给出今天的日期、星期和当前时间，请据此把"明天、后天、大后天、下周三、本周五、周五、3号、9月10日"等相对或省略时间换算成绝对公历日期；"下周三"指下一周的周三，"本周五/周五"指最近的周五（含今天）。
2. "晚7点""晚上7点""7点半""下午7点"统一换算成24小时制（19:00、19:30、19:00）。
3. 只提到时段但没有具体钟点时（如"9月11日晚""周五下午开考"）：晚上/晚/傍晚按19:00、中午按12:00、下午按14:00、上午/早上按09:00填写time且all_day填false；只有完全没有任何时间信息时time才填null且all_day填true。结束时间未知end_time填null。
4. 通知里完全没有日期信息时date填null。
5. location未提到任何地点时填空字符串。
6. title要简明达意，如"班会""实验室例会""高数期中考试"；description保留"带报告""带上笔记本"等关键要求。
7. 【重要】判断通知类型：如果这条通知是对之前已发过的某个日程的变更（如"延期到""改期到""时间调整为""推迟到""顺延至""更改地点为"等），则type填"update"，match_keyword填被修改的事件名称（如"互评大会""班会"，通常是通知标题中的核心词，10字以内），date/time/location填变更后的新值；如果是一条全新的通知，type填"new"，match_keyword填空字符串。"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 调用大模型解析通知原文（阻塞式，请勿在主线程调用） */
    @Throws(Exception::class)
    fun parse(cfg: SettingsStore.Config, rawText: String): ParsedEvent {
        val now = LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        val nowText = String.format(
            "%04d年%02d月%02d日 %s 当前时间 %02d:%02d",
            now.year, now.monthValue, now.dayOfMonth, weekday, now.hour, now.minute
        )
        val userText = "【当前时间】$nowText\n【通知原文】\n$rawText"
        val body = JSONObject()
            .put("model", cfg.model)
            .put("temperature", 0.1)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userText))
            )
        val content = call(cfg, body)
        return extractJson(content)
    }

    /** 设置页"测试连接"：发一条最小请求验证地址/密钥/模型可用 */
    @Throws(Exception::class)
    fun test(cfg: SettingsStore.Config) {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", 8)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "回复：OK"))
            )
        call(cfg, body)
    }

    private fun call(cfg: SettingsStore.Config, body: JSONObject): String {
        if (!cfg.hasKey) throw IOException("未配置 API Key，请到「设置」填写")
        val url = cfg.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${text.take(200)}")
            }
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    /** 容错解析模型输出：剥离 Markdown 代码块、截取首尾大括号之间的JSON */
    private fun extractJson(content: String): ParsedEvent {
        val t = content.replace("```json", "").replace("```", "").trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IOException("模型返回内容不是JSON：${content.take(120)}")
        }
        val obj = JSONObject(t.substring(start, end + 1))

        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = obj.optString(k, "")
                if (v.isNotBlank() && v != "null") return v.trim()
            }
            return null
        }

        val time = str("time", "start_time")
        val typeRaw = str("type")?.lowercase() ?: "new"
        val isUpdate = typeRaw == "update" || typeRaw == "modify" || typeRaw == "change"
        val keyword = str("match_keyword", "matchKeyword", "keyword")
        return ParsedEvent(
            title = str("title", "event_title"),
            date = str("date", "start_date"),
            time = time,
            endTime = str("end_time", "endTime"),
            allDay = obj.optBoolean("all_day", false) || time == null,
            location = str("location") ?: "",
            description = str("description") ?: "",
            type = if (isUpdate) "update" else "new",
            matchKeyword = if (isUpdate && !keyword.isNullOrBlank()) keyword else null
        )
    }
}
