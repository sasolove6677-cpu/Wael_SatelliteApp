package com.satellite.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.webkit.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** يستقبل ترويسات القناة الحالية من صفحة ستالايت: SatBridge.setHeaders(json) */
class SatBridge(private val act: Activity? = null) {
    /** يفتح شاشة كارت الالتقاط الأصلية (UVC) */
    @JavascriptInterface fun openUvc() { act?.runOnUiThread { act.startActivity(android.content.Intent(act, UvcActivity::class.java)) } }
    @Volatile var referer = ""; @Volatile var origin = ""; @Volatile var ua = ""
    @JavascriptInterface fun setHeaders(json: String) {
        try { val o = JSONObject(json); referer = o.optString("referer"); origin = o.optString("origin"); ua = o.optString("userAgent") }
        catch (_: Exception) { referer = ""; origin = ""; ua = "" }
    }
}

/** اهتزاز أصلي (نفس جسر برنامج المحاسبة) */
class AccNative(private val ctx: Context) {
    private val vib: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    @JavascriptInterface fun vibrate(ms: Int) {
        val d = ms.coerceIn(1, 3000).toLong()
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(d)
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val bridge = SatBridge(this)
    private val http = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val mediaRe = Regex("""\.(m3u8|ts|m4s|mp4|aac|mpd|m4a)(\?|$)""", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // إذن الكاميرا والمايك على مستوى النظام (مطلوب لكارت الالتقاط)؛ بدونه يفشل getUserMedia
        if (Build.VERSION.SDK_INT >= 23) {
            val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 7)
        }
        web = WebView(this); setContentView(web)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true; allowFileAccessFromFileURLs = true; allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.addJavascriptInterface(bridge, "SatBridge")
        web.addJavascriptInterface(AccNative(this), "AccNative")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) { runOnUiThread { r.grant(r.resources) } }   // الكاميرا/المايك لكارت الالتقاط
            private var cv: View? = null
            override fun onShowCustomView(v: View, cb: CustomViewCallback) { cv = v; setContentView(v) }
            override fun onHideCustomView() { setContentView(web); cv = null }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                if (req.method != "GET" || !url.startsWith("http") || !mediaRe.containsMatchIn(url)) return null
                if (bridge.referer.isEmpty() && bridge.origin.isEmpty() && bridge.ua.isEmpty()) return null
                val rb = Request.Builder().url(url)
                req.requestHeaders.forEach { (k, v) -> rb.header(k, v) }
                if (bridge.ua.isNotEmpty()) rb.header("User-Agent", bridge.ua)
                if (bridge.referer.isNotEmpty()) rb.header("Referer", bridge.referer)
                if (bridge.origin.isNotEmpty()) rb.header("Origin", bridge.origin)
                return try {
                    val r = http.newCall(rb.build()).execute()
                    val type = r.header("Content-Type")?.substringBefore(";") ?: "application/octet-stream"
                    val h = mutableMapOf("Access-Control-Allow-Origin" to "*")
                    r.headers.names().forEach { n -> if (!n.equals("content-encoding", true)) h[n] = r.header(n)!! }
                    WebResourceResponse(type, r.header("Content-Encoding"), r.code, r.message.ifEmpty { "OK" }, h, r.body?.byteStream())
                } catch (e: Exception) { null }
            }
        }
        web.loadUrl("file:///android_asset/index.html")
    }
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onPause() { super.onPause() }   // يبقى الصوت يعمل عند الخروج؛ لا نوقف الـ WebView
}
