package com.indrhi.emergencias

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class NativeBridge(private val activity: Activity) {

    private val player: EmergencySirenPlayer = EmergencySirenPlayer.getInstance(activity.applicationContext)

    @Volatile
    private var isDownloadingUpdate: Boolean = false

    @JavascriptInterface
    fun testAlarm(): String {
        return try {
            SirenForegroundService.startSiren(
                activity.applicationContext,
                "SIRENA DE EMERGENCIA ACTIVA",
                "Alarma activada manualmente desde el dispositivo."
            )
            val obj = JSONObject()
            obj.put("ok", true)
            obj.put("playing", true)
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun stopAlarm(): String {
        return try {
            SirenForegroundService.stopSiren(activity.applicationContext)
            val obj = JSONObject()
            obj.put("ok", true)
            obj.put("playing", false)
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun isAlarmPlaying(): Boolean {
        return try { player.isPlaying() } catch (t: Throwable) { false }
    }

    @JavascriptInterface
    fun getFcmToken(): String {
        return try {
            val token = MainActivity.cachedFcmToken
            val obj = JSONObject()
            if (token.isNullOrEmpty()) {
                obj.put("ok", false)
                obj.put("error", "token FCM no disponible todavía")
            } else {
                obj.put("ok", true)
                obj.put("token", token)
            }
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun getFirebaseConfig(): String {
        return try {
            val res = activity.resources
            val pkg = activity.packageName
            val apiKeyId = res.getIdentifier("google_api_key", "string", pkg)
            val projectIdId = res.getIdentifier("project_id", "string", pkg)
            val apiKey = if (apiKeyId != 0) res.getString(apiKeyId) else ""
            val projectId = if (projectIdId != 0) res.getString(projectIdId) else "indrhi-emergencias"
            val obj = JSONObject()
            obj.put("ok", apiKey.isNotEmpty())
            obj.put("apiKey", apiKey)
            obj.put("projectId", projectId)
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun subscribeTopic(topic: String): String {
        return try {
            val clean = topic.trim().replace(Regex("[^a-zA-Z0-9-_.~%]"), "_")
            if (clean.isEmpty()) {
                return "{\"ok\":false,\"error\":\"topic vacío\"}"
            }
            FirebaseMessaging.getInstance().subscribeToTopic(clean)
            val obj = JSONObject()
            obj.put("ok", true)
            obj.put("topic", clean)
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun unsubscribeTopic(topic: String): String {
        return try {
            val clean = topic.trim().replace(Regex("[^a-zA-Z0-9-_.~%]"), "_")
            if (clean.isEmpty()) {
                return "{\"ok\":false,\"error\":\"topic vacío\"}"
            }
            FirebaseMessaging.getInstance().unsubscribeFromTopic(clean)
            val obj = JSONObject()
            obj.put("ok", true)
            obj.put("topic", clean)
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun canInstallPackages(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun openUnknownSourcesSettings(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:" + activity.packageName)
            }
            launchSettings(intent, "android.settings.MANAGE_UNKNOWN_APP_SOURCES")
        } else {
            "{\"ok\":true,\"action\":\"not_required\"}"
        }
    }

    @JavascriptInterface
    fun downloadAndInstallUpdate(apkUrl: String): String {
        if (isDownloadingUpdate) {
            return "{\"ok\":false,\"error\":\"Ya hay una descarga en curso\"}"
        }
        val cleanUrl = apkUrl.trim()
        if (!cleanUrl.startsWith("https://")) {
            return "{\"ok\":false,\"error\":\"El enlace debe comenzar por https://\"}"
        }

        isDownloadingUpdate = true
        notifyUpdateState("downloading", "Descargando actualización...")

        Thread {
            try {
                var currentUrl = cleanUrl
                var conn: HttpURLConnection? = null
                var redirects = 0

                while (redirects < 6) {
                    val urlObj = URL(currentUrl)
                    conn = (urlObj.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20000
                        readTimeout = 60000
                        setRequestProperty("User-Agent", "INDRHI-Emergencias-Android")
                    }
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 || code == 308
                    ) {
                        val nextLoc = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        currentUrl = URL(urlObj, nextLoc).toString()
                        redirects++
                    } else {
                        break
                    }
                }

                if (conn == null || conn.responseCode !in 200..299) {
                    val code = conn?.responseCode ?: -1
                    conn?.disconnect()
                    isDownloadingUpdate = false
                    notifyUpdateState("error", "Error HTTP $code al descargar el APK.")
                    return@Thread
                }

                val totalBytes = conn.contentLengthLong
                val outFile = File(activity.cacheDir, "indrhi-update.apk")
                if (outFile.exists()) outFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt()
                                if (pct != lastPct && pct % 10 == 0) {
                                    lastPct = pct
                                    notifyUpdateState("downloading", "Descargando: $pct%")
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                isDownloadingUpdate = false

                if (!canInstallPackages()) {
                    notifyUpdateState("permission_required", "Activa 'Instalar apps desconocidas' para INDRHI Emergencias y luego pulsa Instalar.")
                    openUnknownSourcesSettings()
                } else {
                    notifyUpdateState("ready", "Descarga lista. Abriendo instalador...")
                    launchApkInstaller(outFile)
                }
            } catch (t: Throwable) {
                isDownloadingUpdate = false
                notifyUpdateState("error", "Fallo en descarga: " + (t.message ?: t.javaClass.simpleName))
            }
        }.start()

        return "{\"ok\":true}"
    }

    @JavascriptInterface
    fun installPendingUpdate(): String {
        return try {
            val outFile = File(activity.cacheDir, "indrhi-update.apk")
            if (!outFile.exists()) {
                return "{\"ok\":false,\"error\":\"No hay archivo descargado\"}"
            }
            if (!canInstallPackages()) {
                openUnknownSourcesSettings()
                return "{\"ok\":false,\"error\":\"Falta permiso para instalar aplicaciones\"}"
            }
            launchApkInstaller(outFile)
            "{\"ok\":true}"
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    private fun launchApkInstaller(apkFile: File) {
        activity.runOnUiThread {
            try {
                val apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(installIntent)
            } catch (t: Throwable) {
                notifyUpdateState("error", "No se pudo abrir el instalador: " + (t.message ?: "error"))
            }
        }
    }

    private fun notifyUpdateState(state: String, detail: String) {
        activity.runOnUiThread {
            try {
                val rootWeb = activity.window?.decorView?.findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) as? WebView
                val js = "window.onUpdateDownloadState && window.onUpdateDownloadState(" +
                    JSONObject.quote(state) + "," + JSONObject.quote(detail) + ")"
                rootWeb?.evaluateJavascript(js, null)
            } catch (_: Throwable) {}
        }
    }

    @JavascriptInterface
    fun getHealth(): String {
        return try {
            val obj = JSONObject()
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val sdk = Build.VERSION.SDK_INT

            obj.put("ok", true)
            obj.put("sdkInt", sdk)
            obj.put("alarmPlaying", player.isPlaying())
            obj.put("alarmVolume", am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0)
            obj.put("alarmVolumeMax", am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0)
            obj.put("notificationsEnabled", nm?.areNotificationsEnabled() ?: false)

            val postNotifGranted: Boolean = if (sdk >= 33) {
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            obj.put("postNotificationsGranted", postNotifGranted)

            obj.put("dndAccessGranted", nm?.isNotificationPolicyAccessGranted ?: false)
            obj.put("interruptionFilter", mapInterruptionFilter(nm?.currentInterruptionFilter))

            val fullScreenAllowed: Boolean = if (sdk >= 34 && nm != null) {
                try { nm.canUseFullScreenIntent() } catch (t: Throwable) { true }
            } else {
                true
            }
            obj.put("fullScreenIntentAllowed", fullScreenAllowed)

            val batteryIgnored = try {
                pm?.isIgnoringBatteryOptimizations(activity.packageName) ?: false
            } catch (t: Throwable) {
                false
            }
            obj.put("batteryOptimizationIgnored", batteryIgnored)

            obj.put("ringerMode", mapRingerMode(am?.ringerMode))
            obj.put("headphonesConnected", headphonesConnected(am))
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun getContext(): String {
        return try {
            val obj = JSONObject()
            val pkg = activity.packageName
            val pm = activity.packageManager
            val info = pm.getPackageInfo(pkg, 0)

            obj.put("appName", activity.applicationInfo.loadLabel(pm).toString())
            obj.put("packageName", pkg)
            obj.put("versionName", info.versionName ?: "")
            obj.put("versionCode", PackageInfoCompat.getLongVersionCode(info))
            obj.put("manufacturer", Build.MANUFACTURER ?: "")
            obj.put("model", Build.MODEL ?: "")
            obj.put("androidRelease", Build.VERSION.RELEASE ?: "")
            obj.put("sdkInt", Build.VERSION.SDK_INT)
            obj.put("timestampMs", System.currentTimeMillis())
            obj.put("timezone", java.util.TimeZone.getDefault().id ?: "")
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun openNotificationSettings(): String {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        return launchSettings(intent, "android.settings.APP_NOTIFICATION_SETTINGS")
    }

    @JavascriptInterface
    fun openDndSettings(): String {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        return launchSettings(intent, "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")
    }

    @JavascriptInterface
    fun openFullScreenSettings(): String {
        return if (Build.VERSION.SDK_INT >= 34) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:" + activity.packageName)
            }
            launchSettings(intent, "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT")
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            launchSettings(intent, "android.settings.APP_NOTIFICATION_SETTINGS")
        }
    }

    @JavascriptInterface
    fun openBatterySettings(): String {
        val primary = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return try {
            activity.runOnUiThread {
                try {
                    activity.startActivity(primary)
                } catch (t: Throwable) {
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:" + activity.packageName)
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
            val obj = JSONObject()
            obj.put("ok", true)
            obj.put("action", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    private fun launchSettings(intent: Intent, actionName: String): String {
        return try {
            activity.runOnUiThread {
                try {
                    activity.startActivity(intent)
                } catch (t: Throwable) {
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:" + activity.packageName)
                            }
                        )
                    } catch (_: Throwable) {}
                }
            }
            val obj = JSONObject()
            obj.put("ok", true)
            obj.put("action", actionName)
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    private fun mapInterruptionFilter(filter: Int?): String {
        return when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
            else -> "UNKNOWN"
        }
    }

    private fun mapRingerMode(mode: Int?): String {
        return when (mode) {
            AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            else -> "UNKNOWN"
        }
    }

    private fun headphonesConnected(am: AudioManager?): Boolean {
        if (am == null) return false
        return try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (d in devices) {
                val type = d.type
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_USB_HEADSET
                ) {
                    return true
                }
                if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun errorJson(t: Throwable): String {
        return try {
            val obj = JSONObject()
            obj.put("ok", false)
            obj.put("error", t.message ?: t.javaClass.simpleName)
            obj.toString()
        } catch (_: Throwable) {
            "{\"ok\":false,\"error\":\"unknown\"}"
        }
    }
}
