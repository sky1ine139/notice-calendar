package com.noticecalendar.app.theme

import android.app.Activity
import android.content.Context
import com.noticecalendar.app.R

/**
 * 主题管理器：支持5套预设主题，保存在SharedPreferences，Activity启动时应用。
 */
object ThemeManager {

    private const val PREF_NAME = "theme_pref"
    private const val KEY_THEME = "current_theme"

    enum class Theme(val id: String, val label: String, val styleRes: Int) {
        PURPLE("purple", "紫色（默认）", R.style.Theme_NoticeCal),
        BLUE("blue", "蓝色", R.style.Theme_NoticeCal_Blue),
        GREEN("green", "绿色", R.style.Theme_NoticeCal_Green),
        ORANGE("orange", "橙色", R.style.Theme_NoticeCal_Orange),
        DARK("dark", "深色", R.style.Theme_NoticeCal_Dark);

        companion object {
            fun fromId(id: String?): Theme = values().firstOrNull { it.id == id } ?: PURPLE
        }
    }

    fun getCurrent(context: Context): Theme {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return Theme.fromId(pref.getString(KEY_THEME, Theme.PURPLE.id))
    }

    fun setCurrent(context: Context, theme: Theme) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }

    /** 在Activity的setContentView之前调用 */
    fun apply(activity: Activity) {
        activity.setTheme(getCurrent(activity).styleRes)
    }

    fun allThemes(): List<Theme> = Theme.values().toList()
}
