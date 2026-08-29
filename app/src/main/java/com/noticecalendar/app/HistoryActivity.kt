package com.noticecalendar.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.noticecalendar.app.data.EventRecord
import com.noticecalendar.app.data.EventRepository
import com.noticecalendar.app.databinding.ActivityHistoryBinding
import com.noticecalendar.app.databinding.ItemHistoryBinding
import com.noticecalendar.app.util.TimeUtil

/**
 * 历史记录：回看全部处理过的通知，点击进入编辑页可再次修改、重新写入日历。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = HistoryAdapter(
            onClick = { rec ->
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_RECORD_ID, rec.id)
                )
            },
            onDelete = { rec -> confirmDelete(rec) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = EventRepository.getAll(this)
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(rec: EventRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定删除「${rec.title}」吗？（不会删除已写入系统日历的事件）")
            .setPositiveButton("删除") { _, _ ->
                EventRepository.delete(this, rec.id)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

class HistoryAdapter(
    private val onClick: (EventRecord) -> Unit,
    private val onDelete: (EventRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<EventRecord>()

    fun submit(list: List<EventRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = items[position]
        val b = holder.binding
        b.tvTitle.text = rec.title
        b.tvTime.text = if (rec.source.isBlank()) {
            TimeUtil.formatMillis(rec.startMillis, rec.allDay)
        } else {
            TimeUtil.formatMillis(rec.startMillis, rec.allDay) + " · ${rec.source}"
        }
        b.tvLocation.text = if (rec.location.isBlank()) "无地点" else "地点：${rec.location}"
        b.tvStatus.text = if (rec.calendarEventId > 0) "✔ 已入日历" else "未入日历"
        val ctx = b.root.context
        b.tvStatus.setTextColor(
            ctx.getColor(if (rec.calendarEventId > 0) R.color.status_ok else R.color.status_idle)
        )
        b.root.setOnClickListener { onClick(rec) }
        b.btnDelete.setOnClickListener { onDelete(rec) }
    }
}
