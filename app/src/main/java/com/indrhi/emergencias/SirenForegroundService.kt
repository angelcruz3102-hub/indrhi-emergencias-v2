package com.indrhi.emergencias

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SirenForegroundService : Service() {

    companion object {
        private const val TAG = "SirenFgService"
        private const val NOTIF_ID = 2001

        const val ACTION_START = "com.indrhi.emergencias.ACTION_START_SIREN"
        const val ACTION_STOP = "com.indrhi.emergencias.ACTION_STOP_SIREN"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"

        fun startSiren(context: Context, title: String, body: String) {
            val intent = Intent(context, SirenForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar SirenForegroundService", t)
                EmergencySirenPlayer.getInstance(context.applicationContext).start()
            }
        }

        fun stopSiren(context: Context) {
            try {
                EmergencySirenPlayer.getInstance(context.applicationContext).stop()
            } catch (_: Throwable) {}
            try {
                context.stopService(Intent(context, SirenForegroundService::class.java))
            } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            EmergencySirenPlayer.getInstance(applicationContext).stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "ALERTA DE EMERGENCIA INDRHI"
        val body = intent?.getStringExtra(EXTRA_BODY) ?: "Evacúe el área inmediatamente."

        IndrhiMessagingService.ensureChannels(this)
        val notification = buildCriticalNotification(title, body)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error en startForeground", t)
        }

        val started = EmergencySirenPlayer.getInstance(applicationContext).start()
        if (!started) {
            Log.e(TAG, "EmergencySirenPlayer.start() devolvió false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Si el teléfono está bloqueado o en segundo plano, abrir pantalla roja de emergencia
        if (!MainActivity.isInForeground) {
            try {
                val fullIntent = Intent(this, FullScreenAlertActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_BODY, body)
                }
                startActivity(fullIntent)
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo lanzar FullScreenAlertActivity directamente", t)
            }
        }

        return START_STICKY
    }

    private fun buildCriticalNotification(title: String, body: String): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val fullScreenIntent = Intent(this, FullScreenAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 201, fullScreenIntent, piFlags)

        val stopIntent = Intent(this, SirenForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 202, stopIntent, piFlags)

        return NotificationCompat.Builder(this, IndrhiMessagingService.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "DETENER SIRENA", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        EmergencySirenPlayer.getInstance(applicationContext).stop()
        super.onDestroy()
    }
}
