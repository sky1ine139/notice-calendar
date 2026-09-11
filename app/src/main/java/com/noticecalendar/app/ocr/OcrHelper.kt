package com.noticecalendar.app.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.IOException

/**
 * 图片转文字：ML Kit 本地文字识别（离线推理，图片不上传服务器）。
 * 中文识别器同时支持中英文混排；识别结果按行输出，交给用户核对后再走原有解析流程。
 */
object OcrHelper {

    /** 识别结果：text=可直接填入输入框的文本；blocks=识别到的文本块个数 */
    data class Result(val text: String, val blocks: Int)

    private val chineseOptions by lazy { ChineseTextRecognizerOptions.Builder().build() }
    private val recognizer by lazy { TextRecognition.getClient(chineseOptions) }

    /** 单张图片的像素上限，超过则先降采样，避免大图 OOM（截图远小于此值，不受影响） */
    private const val MAX_PIXELS = 4000 * 4000

    /**
     * 识别图片中的文字（阻塞式，请勿在主线程调用）。
     * 首次使用若本机没有中文识别模型，ML Kit 会自动下载小体积模型，此时耗时较长。
     */
    @Throws(Exception::class)
    fun recognizeBlocking(context: Context, uri: Uri): Result {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: OutOfMemoryError) {
            InputImage.fromBitmap(decodeDownscaled(context, uri), 0)
        }
        val visionText = com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
        return Result(
            text = toNoticeText(visionText.text),
            blocks = visionText.textBlocks.size
        )
    }

    /** 降采样解码，仅在直接解码内存不足时作为兜底 */
    private fun decodeDownscaled(context: Context, uri: Uri): android.graphics.Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth > 0 && opts.outHeight > 0 &&
            (opts.outWidth.toLong() * opts.outHeight.toLong()) / (sample.toLong() * sample) > MAX_PIXELS
        ) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: throw IOException("无法读取图片")
    }

    /**
     * 把 OCR 原始输出整理成适合输入框的文本：
     * 1. 去掉每行首尾空白与多余空行
     * 2. 合并被 OCR 误断开的行（如"……互评大会的" + "通知"、字段名与值分处两行）
     *
     * 合并必须保守：宁可少合并（用户扫一眼就能看出），也不要错合并
     * （把"时间：…""地点：…"吞进上一行会让解析彻底跑偏）。
     */
    fun toNoticeText(raw: String): String {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""

        val merged = ArrayList<String>(lines.size)
        var buffer = StringBuilder()
        for (line in lines) {
            if (buffer.isEmpty()) {
                buffer.append(line)
            } else if (shouldMerge(buffer.toString(), line)) {
                buffer.append(line)
            } else {
                merged.add(buffer.toString())
                buffer = StringBuilder(line)
            }
        }
        if (buffer.isNotEmpty()) merged.add(buffer.toString())
        return merged.joinToString("\n")
    }

    /** 上一行以句末标点收尾，说明这句话已说完，下一行是新内容 */
    private val SENTENCE_END = Regex("[。！？!?；;]$")

    /**
     * 行首是字段名/标签（时间、地点、教室…）或"xxx："形式，说明这是新起的一行字段，
     * 绝不能并进上一行。字段名允许前面带最多 6 个限定字（如"候场地点在""面试地点在"）。
     */
    private val LEADING_LABEL = Regex(
        "^[^：:]{1,10}[：:]|^[^，,。！？!?\\s]{0,6}" +
            "(时间|日期|地点|教室|会议|考场|备注|要求|对象|联系人|主办|承办|内容|主题|报名|签到|集合)在?"
    )

    /** 以「在」「到」「为」等结尾的悬空行，其取值就在下一行 */
    private val TRAILING_OPEN_LABEL = Regex("[在到为是]$")

    private fun shouldMerge(prev: String, next: String): Boolean {
        if (SENTENCE_END.containsMatchIn(prev)) return false
        // 下一行是新字段/新条目，或明显是一整句，都不要并进来
        if (LEADING_LABEL.containsMatchIn(next)) return false
        if (NUMBERED_OR_BULLET.containsMatchIn(next)) return false
        if (next.startsWith("【") || next.startsWith("-") || next.startsWith("·")) return false
        if (next.length > 12) return false
        if (TRAILING_OPEN_LABEL.containsMatchIn(prev)) return true
        return true
    }

    private val NUMBERED_OR_BULLET = Regex("^\\s*(\\d+[.、)）]|[一二三四五六七八九十]+[、.]|[-*•·])\\s*.*$")
}
