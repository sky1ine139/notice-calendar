package com.noticecalendar.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 历史记录存储：全部数据以 JSON 文件形式保存在应用私有目录（filesDir/records.json），
 * 完全本地化，不依赖任何账号或云端。
 */
object EventRepository {

    private const val FILE_NAME = "records.json"
    private val lock = Any()
    private var cache: MutableList<EventRecord>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun all(context: Context): MutableList<EventRecord> {
        synchronized(lock) {
            cache?.let { return it }
            val list = mutableListOf<EventRecord>()
            val f = file(context)
            if (f.exists()) {
                runCatching {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        list.add(EventRecord.fromJson(arr.getJSONObject(i)))
                    }
                }
            }
            cache = list
            return list
        }
    }

    fun getAll(context: Context): List<EventRecord> =
        all(context).sortedByDescending { it.startMillis }

    fun getById(context: Context, id: Long): EventRecord? =
        all(context).firstOrNull { it.id == id }

    fun add(context: Context, record: EventRecord) {
        synchronized(lock) {
            all(context).add(record)
            persist(context)
        }
    }

    fun update(context: Context, record: EventRecord) {
        synchronized(lock) {
            val list = all(context)
            val idx = list.indexOfFirst { it.id == record.id }
            if (idx >= 0) {
                list[idx] = record
                persist(context)
            }
        }
    }

    fun delete(context: Context, id: Long) {
        synchronized(lock) {
            val list = all(context)
            if (list.removeAll { it.id == id }) persist(context)
        }
    }

    /** 调用时必须已持有 lock（all 内部 synchronized 可重入） */
    private fun persist(context: Context) {
        val arr = JSONArray()
        all(context).forEach { arr.put(it.toJson()) }
        runCatching { file(context).writeText(arr.toString()) }
    }
}
