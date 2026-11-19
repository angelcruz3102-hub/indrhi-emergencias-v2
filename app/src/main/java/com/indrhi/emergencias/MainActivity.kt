package com.indrhi.emergencias

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val bridge by lazy { NativeBridge(this) }

    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* sin acción: el panel de salud refleja el estado real */ }

    companion object {
        private const val TAG = "MainActivity"
        private const val BRIDGE_NAME = "IndrhiBridge"
        private const val ENTRY_URL = "file:///android_asset/web/index.html"

        @Volatile
        var isInForeground: Boolean = false

        @Volatile
        var cachedFcmToken: String? = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            FirebaseApp.initializeApp(this)
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo inicializar FirebaseApp", t)
        }

        try {
            IndrhiMessagingService.ensureChannels(this)
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudieron crear los canales de notificación", t)
        }

        WebView.setWebContentsDebuggingEnabled(false)

        val createdWebView = WebView(this)
        webView = createdWebView
        setContentView(createdWebView)

        with(createdWebView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
        }

        createdWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                if (url.startsWith("file:///android_asset/")) {
                    return false
                }
                if (url.startsWith("https://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (t: Throwable) {
                        Log.e(TAG, "No se pudo abrir enlace externo: $url", t)
                    }
                }
                return true
            }
        }

        createdWebView.webChromeClient = WebChromeClient()
        createdWebView.addJavascriptInterface(bridge, BRIDGE_NAME)
        createdWebView.loadUrl(ENTRY_URL)

        fetchFcmToken()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.handleAndroidBack ? window.handleAndroidBack() : false"
                        ) { result ->
                            if (result != "true") {
                                moveTaskToBack(true)
                            }
                        }
                    } else {
                        moveTaskToBack(true)
                    }
                }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun fetchFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrEmpty()) {
                        cachedFcmToken = token
                        Log.i("IndrhiFCM", "Token FCM disponible: $token")
                        runOnUiThread {
                            if (::webView.isInitialized) {
                                val js = "window.onFcmToken && window.onFcmToken(" + JSONObject.quote(token) + ")"
                                webView.evaluateJavascript(js, null)
                            }
                        }
                    } else {
                        Log.e(TAG, "FirebaseMessaging devolvió token vacío")
                    }
                } else {
                    Log.e(TAG, "No se pudo obtener el token FCM", task.exception)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error solicitando token FCM", t)
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.refreshHealth && window.refreshHealth()", null)
            cachedFcmToken?.let { token ->
                val js = "window.onFcmToken && window.onFcmToken(" + JSONObject.quote(token) + ")"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
