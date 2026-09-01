package com.noticecalendar.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * 打赏页：展示微信/支付宝收款码，完全自愿，不影响功能使用。
 */
class DonateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.noticecalendar.app.theme.ThemeManager.apply(this)
        title = "支持一下"

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/donate.html")
        }
        setContentView(webView)
    }
}
