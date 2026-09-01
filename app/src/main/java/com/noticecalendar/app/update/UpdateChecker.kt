package com.noticecalendar.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 检查GitHub最新Release版本，有更新则提示用户。
 * 静默失败：网络错误时不提示用户，不影响使用。
 */
object UpdateChecker {

    private const val GITHUB_API = "https://api.github.com/repos/sky1ine139/notice-calendar/releases/latest"
    private const val GITHUB_RELEASE_PAGE = "https://github.com/sky1ine139/notice-calendar/releases"

    data class ReleaseInfo(
        val version: String,      // 版本号，如 "1.2.0"
        val title: String,        // Release标题
        val notes: String,        // 更新日志
        val htmlUrl: String       // Release页面链接
    )

    /**
     * 检查更新，有新版本时弹出对话框。
     * @param silent true=静默检查（无更新不提示），false=手动检查（无更新也提示）
     */
    fun check(activity: Activity, silent: Boolean = false) {
        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (!silent) showNoUpdate(activity)
                    return@Thread
                }
                val json = response.body?.string() ?: return@Thread
                val release = parseRelease(json) ?: return@Thread

                val currentVersion = getCurrentVersion(activity)
                if (isNewer(release.version, currentVersion)) {
                    activity.runOnUiThread {
                        showUpdateDialog(activity, release)
                    }
                } else if (!silent) {
                    activity.runOnUiThread {
                        showNoUpdate(activity)
                    }
                }
            } catch (e: Exception) {
                // 网络错误，静默失败
                if (!silent) {
                    activity.runOnUiThread {
                        showCheckFailed(activity)
                    }
                }
            }
        }.start()
    }

    private fun parseRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "")
            val version = tagName.removePrefix("v").removePrefix("V")
            val title = obj.optString("name", tagName)
            val notes = obj.optString("body", "")
            val htmlUrl = obj.optString("html_url", GITHUB_RELEASE_PAGE)
            if (version.isBlank()) null else ReleaseInfo(version, title, notes, htmlUrl)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    /** 比较版本号，如 "1.3.0" > "1.2.0" */
    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun showUpdateDialog(activity: Activity, release: ReleaseInfo) {
        val notesText = if (release.notes.isNotBlank()) {
            "\n\n更新日志：\n${release.notes.take(500)}"
        } else ""

        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${release.version}")
            .setMessage("当前版本：${getCurrentVersion(activity)}\n最新版本：${release.version}$notesText")
            .setPositiveButton("去下载") { _, _ ->
                openInBrowser(activity, release.htmlUrl)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun showNoUpdate(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("检查更新")
            .setMessage("当前已是最新版本 v${getCurrentVersion(activity)}")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showCheckFailed(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("检查更新失败")
            .setMessage("网络连接失败，无法检查更新。\n你可以手动访问 GitHub 查看最新版本。")
            .setPositiveButton("去GitHub") { _, _ ->
                openInBrowser(activity, GITHUB_RELEASE_PAGE)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openInBrowser(activity: Activity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        activity.startActivity(intent)
    }
}
