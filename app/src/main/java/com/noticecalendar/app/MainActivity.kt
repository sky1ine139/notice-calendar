package com.noticecalendar.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.noticecalendar.app.data.EventRecord
import com.noticecalendar.app.data.EventRepository
import com.noticecalendar.app.data.SettingsStore
import com.noticecalendar.app.databinding.ActivityMainBinding
import com.noticecalendar.app.llm.LlmClient
import com.noticecalendar.app.llm.ParsedEvent
import com.noticecalendar.app.parse.LocalFallbackParser
import com.noticecalendar.app.util.TimeUtil
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 首页：粘贴通知 → 调大模型解析 → 自动存入历史 → 跳转编辑确认页
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.noticecalendar.app.theme.ThemeManager.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnParse.setOnClickListener { startParse() }
        binding.cardDonate.setOnClickListener {
            startActivity(Intent(this, DonateActivity::class.java))
        }
    }

    /** Android 10+ 前台应用读取剪贴板无需任何权限声明 */
    private fun pasteFromClipboard() {
        val text = try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) {
            toast("剪贴板里没有文本")
        } else {
            binding.input.setText(text)
            binding.input.setSelection(text.length)
        }
    }

    private fun startParse() {
        val raw = binding.input.text.toString().trim()
        if (raw.isEmpty()) {
            toast("请先粘贴群通知内容")
            return
        }
        setLoading(true)
        val cfg = SettingsStore.load(this)
        Thread {
            var err: String? = null
            val result: Pair<ParsedEvent, String> = if (!cfg.hasKey) {
                LocalFallbackParser.parse(raw) to "本地解析"
            } else {
                try {
                    LlmClient.parse(cfg, raw) to "AI解析"
                } catch (e: Exception) {
                    err = e.message ?: e.toString()
                    LocalFallbackParser.parse(raw) to "本地解析"
                }
            }
            val (parsed, source) = result
            val isUpdate = parsed.type == "update" && !parsed.matchKeyword.isNullOrBlank()
            val matchedOld = if (isUpdate) findMatchingRecord(parsed.matchKeyword!!) else null
            val newRec = buildRecord(parsed, raw, source)

            val record: EventRecord
            val updateMode: Boolean
            val oldStartMillis: Long
            val oldTitle: String
            // 验证：匹配到的旧记录时间必须和新时间不同，否则说明匹配错了（可能匹配到上一次错误创建的记录）
            val timeChanged = matchedOld != null && matchedOld.startMillis != newRec.startMillis
            if (timeChanged && matchedOld != null) {
                // 更新模式：复用原记录的 id 和 calendarEventId，只更新时间等字段
                // 标题保留原日程标题（变更通知通常不会重复完整事件名，LLM易解析错）
                record = matchedOld.copy(
                    title = matchedOld.title,
                    startMillis = newRec.startMillis,
                    endMillis = newRec.endMillis,
                    allDay = newRec.allDay,
                    location = if (newRec.location.isNotBlank()) newRec.location else matchedOld.location,
                    description = newRec.description.ifBlank { matchedOld.description },
                    rawText = raw,
                    source = source
                )
                oldStartMillis = matchedOld.startMillis
                oldTitle = matchedOld.title
                EventRepository.update(this, record)
                updateMode = true
            } else {
                record = newRec
                oldStartMillis = 0L
                oldTitle = ""
                EventRepository.add(this, record)
                updateMode = false
            }

            runOnUiThread {
                setLoading(false)
                val errMsg = err
                if (errMsg != null) {
                    toast("AI解析失败：${errMsg.take(80)}，已用本地规则粗略解析，请校正")
                }
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_RECORD_ID, record.id)
                        .putExtra(EditorActivity.EXTRA_UPDATE_MODE, updateMode)
                        .putExtra(EditorActivity.EXTRA_OLD_START_MILLIS, oldStartMillis)
                        .putExtra(EditorActivity.EXTRA_OLD_TITLE, oldTitle)
                )
                binding.input.setText("")
            }
        }.start()
    }

    /** 把模型输出的结构化字段转成事件记录；时间换算基于手机当前日期/时区 */
    private fun buildRecord(p: ParsedEvent, raw: String, source: String): EventRecord {
        val zone = ZoneId.systemDefault()
        val date = TimeUtil.parseDate(p.date) ?: LocalDate.now()
        val time = TimeUtil.parseTime(p.time)
        val endTime = TimeUtil.parseTime(p.endTime)
        val allDay = p.allDay || time == null

        val start: Long
        val endMs: Long
        if (allDay || time == null) {
            start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            endMs = start + TimeUtil.DAY_MS
        } else {
            start = date.atTime(time.hour, time.minute).atZone(zone).toInstant().toEpochMilli()
            endMs = if (endTime != null && endTime.isAfter(time)) {
                date.atTime(endTime.hour, endTime.minute).atZone(zone).toInstant().toEpochMilli()
            } else {
                start + TimeUtil.HOUR_MS
            }
        }

        val now = System.currentTimeMillis()
        return EventRecord(
            id = now,
            rawText = raw,
            title = p.title?.take(30).takeUnless { it.isNullOrEmpty() } ?: "未命名事件",
            startMillis = start,
            endMillis = endMs,
            allDay = allDay,
            location = p.location.orEmpty().take(50),
            description = p.description.orEmpty(),
            reminderMinutes = 30,
            calendarEventId = -1L,
            createdAt = now,
            source = source
        )
    }

    /** 在历史记录中查找标题包含关键词的最近一条日程（用于更新匹配） */
    private fun findMatchingRecord(keyword: String): EventRecord? {
        val kw = keyword.trim()
        if (kw.length < 2) return null
        val all = EventRepository.getAll(this)
        // 标题包含关键词，按开始时间倒序取最近的
        return all
            .filter { it.title.contains(kw, ignoreCase = true) }
            .maxByOrNull { it.startMillis }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnParse.isEnabled = !loading
        binding.btnPaste.isEnabled = !loading
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
