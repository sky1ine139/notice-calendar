package com.noticecalendar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.noticecalendar.app.calendar.CalendarHelper
import com.noticecalendar.app.data.SettingsStore
import com.noticecalendar.app.databinding.ActivitySettingsBinding
import com.noticecalendar.app.llm.LlmClient

/**
 * 设置页：填写大模型 API 地址、密钥、模型名；选择写入的日历账户。密钥仅保存在本机。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CALENDAR_LIST = 102
    }

    private lateinit var binding: ActivitySettingsBinding
    private var pendingPickCalendar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cfg = SettingsStore.load(this)
        binding.etBaseUrl.setText(cfg.baseUrl)
        binding.etApiKey.setText(cfg.apiKey)
        binding.etModel.setText(cfg.model)

        binding.btnSave.setOnClickListener {
            SettingsStore.save(
                this,
                baseUrl = binding.etBaseUrl.text.toString(),
                apiKey = binding.etApiKey.text.toString(),
                model = binding.etModel.text.toString()
            )
            Toast.makeText(this, "已保存（密钥仅保存在本机）", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnTest.setOnClickListener {
            val testCfg = SettingsStore.Config(
                baseUrl = binding.etBaseUrl.text.toString(),
                apiKey = binding.etApiKey.text.toString(),
                model = binding.etModel.text.toString()
            )
            binding.tvTestResult.text = "测试中…"
            binding.btnTest.isEnabled = false
            Thread {
                val msg = try {
                    LlmClient.test(testCfg)
                    "✅ 连接成功，模型可正常调用"
                } catch (e: Exception) {
                    "❌ 失败：${e.message?.take(160) ?: e.toString()}"
                }
                runOnUiThread {
                    binding.tvTestResult.text = msg
                    binding.btnTest.isEnabled = true
                }
            }.start()
        }

        binding.tvCalendarAccount.setOnClickListener { onPickCalendarClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshCalendarLabel()
    }

    private fun hasCalendarRead(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun refreshCalendarLabel() {
        val prefId = SettingsStore.load(this).preferredCalendarId
        if (prefId <= 0L) {
            binding.tvCalendarAccount.text = "自动（本地日历优先）"
            return
        }
        if (!hasCalendarRead()) {
            binding.tvCalendarAccount.text = "已指定（ID $prefId）"
            return
        }
        val found = CalendarHelper.listCalendars(this).firstOrNull { it.id == prefId }
        binding.tvCalendarAccount.text = if (found != null) {
            CalendarHelper.label(found)
        } else {
            "原账户已不存在，点击重新选择"
        }
    }

    private fun onPickCalendarClicked() {
        if (!hasCalendarRead()) {
            pendingPickCalendar = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_CALENDAR_LIST
            )
            return
        }
        showCalendarPicker()
    }

    private fun showCalendarPicker() {
        val cals = CalendarHelper.listCalendars(this)
        if (cals.isEmpty()) {
            Toast.makeText(this, "未找到可写的日历账户", Toast.LENGTH_SHORT).show()
            return
        }
        val names = mutableListOf("自动（本地日历优先）")
        cals.forEach { names.add(CalendarHelper.label(it)) }
        val current = SettingsStore.load(this).preferredCalendarId
        val checked = if (current <= 0L) 0 else cals.indexOfFirst { it.id == current } + 1

        AlertDialog.Builder(this)
            .setTitle("选择写入的日历账户")
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                val id = if (which == 0) 0L else cals[which - 1].id
                SettingsStore.saveCalendarPref(this, id)
                refreshCalendarLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALENDAR_LIST) return
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (pendingPickCalendar) {
            pendingPickCalendar = false
            if (ok) showCalendarPicker()
            else Toast.makeText(this, "未授予日历权限，无法列出账户", Toast.LENGTH_LONG).show()
        }
    }
}
