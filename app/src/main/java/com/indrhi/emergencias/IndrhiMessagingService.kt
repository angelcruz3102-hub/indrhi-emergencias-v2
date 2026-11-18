package com.indrhi.emergencias

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class IndrhiMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "IndrhiFCM"
        const val CHANNEL_ALARM = "indrhi_alarm"
        const val CHANNEL_INFO = "indrhi_info"
        private const val NOTIF_INFO_ID = 1002

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
                val alarmAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val soundUri = Uri.parse("android.resource://" + context.packageName + "/" + R.raw.sirena_emergencia)
                val ch = NotificationChannel(
                    CHANNEL_ALARM,
                    "Alarmas críticas INDRHI",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Sirena de evacuación y notificaciones críticas"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 800L, 400L)
                    setSound(soundUri, alarmAttrs)
                    enableLights(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    try { setBypassDnd(true) } catch (_: Throwable) {}
                }
                nm.createNotificationChannel(ch)
            }

            if (nm.getNotificationChannel(CHANNEL_INFO) == null) {
                val ch = NotificationChannel(
                    CHANNEL_INFO,
                    "Informativas INDRHI",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notificaciones informativas sin sonido"
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Nuevo token FCM: $token")
        try {
            MainActivity.cachedFcmToken = token
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo propagar el token a MainActivity", t)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val action = data["action"] ?: ""
        val title = data["title"] ?: "ALERTA DE EMERGENCIA INDRHI"
        val body = data["body"] ?: "Evacúe el área inmediatamente."

        Log.i(TAG, "onMessageReceived action=$action title=$title")

        ensureChannels(this)

        if (action == "start_siren") {
            SirenForegroundService.startSiren(applicationContext, title, body)
        } else if (action == "stop_siren") {
            SirenForegroundService.stopSiren(applicationContext)
        } else {
            postInfoNotification(title, body)
        }
    }

    private fun postInfoNotification(title: String, body: String) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(this, NOTIF_INFO_ID, intent, piFlags)

            val builder = NotificationCompat.Builder(this, CHANNEL_INFO)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pi)

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIF_INFO_ID, builder.build())
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo publicar notificación", t)
        }
    }
}
