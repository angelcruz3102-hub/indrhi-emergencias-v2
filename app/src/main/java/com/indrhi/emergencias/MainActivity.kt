package com.indrhi.emergencias

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val bridge by lazy { NativeBridge(this) }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != Activity.RESULT_OK) {
            pendingCameraUri = null
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris: Array<Uri>? = when {
            data?.clipData != null && data.clipData!!.itemCount > 0 -> {
                Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            pendingCameraUri != null -> arrayOf(pendingCameraUri!!)
            else -> null
        }
        pendingCameraUri = null
        callback.onReceiveValue(uris)
    }

    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
            allowContentAccess = true
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
                if (request?.isForMainFrame == false) {
                    return false
                }
                if (url.startsWith("tel:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    } catch (t: Throwable) {
                        Log.e(TAG, "No se pudo abrir marcador: $url", t)
                    }
                    return true
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

        createdWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                pendingCameraUri = null

                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }

                val extraIntents = ArrayList<Intent>()
                try {
                    val photoFile = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                    val photoUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "$packageName.fileprovider",
                        photoFile
                    )
                    val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    pendingCameraUri = photoUri
                    extraIntents.add(camIntent)
                } catch (t: Throwable) {
                    Log.w(TAG, "FileProvider no disponible para cámara directa", t)
                    pendingCameraUri = null
                    extraIntents.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                }

                extraIntents.add(Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60)
                })

                return try {
                    val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, galleryIntent)
                        putExtra(Intent.EXTRA_TITLE, "Tomar o seleccionar foto / video")
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                    }
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "Fallback selector simple", t)
                    try {
                        fileChooserLauncher.launch(galleryIntent)
                        true
                    } catch (e2: Throwable) {
                        this@MainActivity.filePathCallback = null
                        filePathCallback?.onReceiveValue(null)
                        false
                    }
                }
            }
        }

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
                        runOnUiThread {
                            if (::webView.isInitialized) {
                                val js = "window.onFcmToken && window.onFcmToken(" + JSONObject.quote(token) + ")"
                                webView.evaluateJavascript(js, null)
                            }
                        }
                    }
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
