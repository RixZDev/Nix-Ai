package com.nixai.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var models: ModelManager
    private lateinit var llm: LlmEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        models = ModelManager(this)
        llm = LlmEngine(applicationContext, scope)

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#131314"))
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(Bridge(), "NixNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    return true
                }
                return false
            }
        }

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl("file:///android_asset/index.html")

        // Si el modelo ya está descargado, lo cargamos en segundo plano al abrir la app
        if (models.isReady()) llm.loadAsync(models.modelFile.path)
    }

    private fun js(code: String) = main.post { webView.evaluateJavascript(code, null) }

    inner class Bridge {

        @JavascriptInterface
        fun getState(): String {
            val dl = models.downloadState()
            if (dl.optString("state") == "ready" && !llm.isLoaded && !llm.loading && llm.loadError == null) {
                llm.loadAsync(models.modelFile.path)
            }
            return JSONObject()
                .put("download", dl)
                .put("loaded", llm.isLoaded)
                .put("loading", llm.loading)
                .put("error", llm.loadError ?: JSONObject.NULL)
                .put("freeBytes", models.freeBytes())
                .put("neededBytes", ModelSpec.NEEDED_FREE_BYTES)
                .toString()
        }

        @JavascriptInterface
        fun startDownload(wifiOnly: Boolean) {
            models.startDownload(wifiOnly)
        }

        @JavascriptInterface
        fun retryLoad() {
            if (models.isReady()) {
                llm.close()
                llm.loadAsync(models.modelFile.path)
            }
        }

        @JavascriptInterface
        fun resetModel() {
            llm.close()
            models.cancelDownload()
        }

        @JavascriptInterface
        fun generate(id: String, historyJson: String, system: String, temperature: Double) {
            val arr = JSONArray(historyJson)
            val history = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ChatMsg(o.getString("role"), o.getString("content"))
            }
            val q = JSONObject.quote(id)
            llm.generate(
                history, system, temperature,
                onChunk = { js("NixNativeCb.token($q, ${JSONObject.quote(it)})") },
                onDone = { js("NixNativeCb.done($q)") },
                onError = { js("NixNativeCb.error($q, ${JSONObject.quote(it)})") }
            )
        }

        @JavascriptInterface
        fun stop() {
            llm.stop()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        llm.close()
        webView.destroy()
        super.onDestroy()
    }
}
