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
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject

class NativeBridge(private val activity: Activity) {

    private val player: EmergencySirenPlayer = EmergencySirenPlayer.getInstance(activity.applicationContext)

    @JavascriptInterface
    fun testAlarm(): String {
        return try {
            val started = player.start()
            val obj = JSONObject()
            obj.put("ok", started)
            obj.put("playing", player.isPlaying())
            obj.toString()
        } catch (t: Throwable) {
            errorJson(t)
        }
    }

    @JavascriptInterface
    fun stopAlarm(): String {
        return try {
            player.stop()
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
