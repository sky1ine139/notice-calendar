package com.noticecalendar.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.noticecalendar.app.calendar.CalendarHelper
import com.noticecalendar.app.data.EventRecord
import com.noticecalendar.app.data.EventRepository
import com.noticecalendar.app.data.SettingsStore
import com.noticecalendar.app.databinding.ActivityEditorBinding
import com.noticecalendar.app.util.TimeUtil
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 编辑确认页：展示解析结果供人工校正，确认后一键写入系统日历并设置提醒。
 * 首页解析完成后跳转到这里；历史记录点击条目也跳转到这里。
 */
class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
        private const val REQ_CALENDAR = 101
        private const val HOUR_MS = TimeUtil.HOUR_MS
        private const val DAY_MS = TimeUtil.DAY_MS

        private val REMINDER_LABELS = listOf("不提醒", "提前10分钟", "提前30分钟", "提前1小时", "提前2小时", "提前1天")
        private val REMINDER_VALUES = listOf(-1, 10, 30, 60, 120, 1440)
    }

    private lateinit var binding: ActivityEditorBinding
    private lateinit var record: EventRecord
    private var pendingWrite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rec = EventRepository.getById(this, intent.getLongExtra(EXTRA_RECORD_ID, -1L))
        if (rec == null) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        record = rec

        binding.spinnerReminder.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, REMINDER_LABELS
        )
        binding.spinnerReminder.setSelection(
            REMINDER_VALUES.indexOf(record.reminderMinutes).coerceAtLeast(0)
        )

        binding.etTitle.setText(record.title)
        binding.etLocation.setText(record.location)
        binding.etDescription.setText(record.description)
        binding.cbAllDay.isChecked = record.allDay
        binding.tvRaw.text = record.rawText
        binding.tvRaw.setOnClickListener {
            binding.tvRaw.maxLines = if (binding.tvRaw.maxLines == 8) Int.MAX_VALUE else 8
        }
        val src = if (record.source.isBlank()) "" else "来源：${record.source} · "
        binding.tvStatus.text = src + if (record.calendarEventId > 0) {
            "已写入系统日历（事件ID ${record.calendarEventId}），可再次写入"
        } else {
            "尚未写入日历"
        }

        // 先赋值再注册监听，避免程序化赋值触发回调
        binding.cbAllDay.setOnCheckedChangeListener { _, checked -> onAllDayChanged(checked) }
        binding.tvDateTime.setOnClickListener { showDatePicker() }
        binding.btnWriteCalendar.setOnClickListener { onWriteClicked() }
        binding.btnSave.setOnClickListener { onSaveClicked() }

        refreshDateTime()
    }

    private fun onAllDayChanged(checked: Boolean) {
        record.allDay = checked
        val z = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(record.startMillis).atZone(z).toLocalDate()
        record.startMillis = if (checked) {
            d.atStartOfDay(z).toInstant().toEpochMilli()
        } else {
            d.atTime(9, 0).atZone(z).toInstant().toEpochMilli()
        }
        record.endMillis = record.startMillis + if (checked) DAY_MS else HOUR_MS
        refreshDateTime()
    }

    private fun showDatePicker() {
        val z = ZoneId.systemDefault()
        val cur = Instant.ofEpochMilli(record.startMillis).atZone(z)
        DatePickerDialog(
            this,
            { _, y, m0, d -> applyDate(y, m0, d) },
            cur.year, cur.monthValue - 1, cur.dayOfMonth
        ).show()
    }

    private fun applyDate(y: Int, m0: Int, d: Int) {
        val z = ZoneId.systemDefault()
        val oldStart = record.startMillis
        val oldCur = Instant.ofEpochMilli(oldStart).atZone(z)
        val newDate = LocalDate.of(y, m0 + 1, d)
        record.startMillis = if (record.allDay) {
            newDate.atStartOfDay(z).toInstant().toEpochMilli()
        } else {
            newDate.atTime(oldCur.hour, oldCur.minute).atZone(z).toInstant().toEpochMilli()
        }
        val dur = (record.endMillis - oldStart).takeIf { it > 0 } ?: HOUR_MS
        record.endMillis = record.startMillis + (if (record.allDay) DAY_MS else dur)
        refreshDateTime()
        if (!record.allDay) showTimePicker()
    }

    private fun showTimePicker() {
        val z = ZoneId.systemDefault()
        val cur = Instant.ofEpochMilli(record.startMillis).atZone(z)
        TimePickerDialog(
            this,
            { _, h, min -> applyTime(h, min) },
            cur.hour, cur.minute, true
        ).show()
    }

    private fun applyTime(h: Int, min: Int) {
        val z = ZoneId.systemDefault()
        val oldStart = record.startMillis
        val oldDur = (record.endMillis - oldStart).takeIf { it > 0 } ?: HOUR_MS
        val d = Instant.ofEpochMilli(oldStart).atZone(z).toLocalDate()
        record.startMillis = d.atTime(h, min).atZone(z).toInstant().toEpochMilli()
        record.endMillis = record.startMillis + oldDur
        refreshDateTime()
    }

    private fun refreshDateTime() {
        binding.tvDateTime.text = TimeUtil.formatMillis(record.startMillis, record.allDay)
    }

    private fun syncFieldsFromUi() {
        record.title = binding.etTitle.text.toString().trim().ifEmpty { "未命名事件" }
        record.location = binding.etLocation.text.toString().trim()
        record.description = binding.etDescription.text.toString().trim()
        record.allDay = binding.cbAllDay.isChecked
        val pos = binding.spinnerReminder.selectedItemPosition
        if (pos in REMINDER_VALUES.indices) record.reminderMinutes = REMINDER_VALUES[pos]
    }

    private fun onWriteClicked() {
        syncFieldsFromUi()
        EventRepository.update(this, record)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingWrite = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_CALENDAR
            )
            return
        }
        doWrite()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALENDAR) return
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (pendingWrite) {
            pendingWrite = false
            if (ok) {
                doWrite()
            } else {
                Toast.makeText(this, "未授予日历权限，无法写入。可在系统设置-应用权限中开启", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun doWrite() {
        binding.btnWriteCalendar.isEnabled = false
        val app = this
        Thread {
            try {
                val eventId = CalendarHelper.insertEvent(
                    app, record, SettingsStore.load(app).preferredCalendarId
                )
                record.calendarEventId = eventId
                EventRepository.update(app, record)
                runOnUiThread {
                    binding.tvStatus.text = "已写入系统日历（事件ID $eventId），可再次写入"
                    Toast.makeText(app, "已写入系统日历，并设置提醒", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(app, "写入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { binding.btnWriteCalendar.isEnabled = true }
            }
        }.start()
    }

    private fun onSaveClicked() {
        syncFieldsFromUi()
        EventRepository.update(this, record)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
