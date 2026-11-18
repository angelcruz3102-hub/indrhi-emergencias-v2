package com.indrhi.emergencias

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class EmergencySirenPlayer private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "EmergencySirenPlayer"

        @Volatile
        private var instance: EmergencySirenPlayer? = null

        fun getInstance(context: Context): EmergencySirenPlayer {
            val existing = instance
            if (existing != null) return existing
            synchronized(EmergencySirenPlayer::class.java) {
                val current = instance
                if (current != null) return current
                val created = EmergencySirenPlayer(context.applicationContext)
                instance = created
                return created
            }
        }
    }

    private val lock = Any()

    @Volatile
    private var playing: Boolean = false

    private var mediaPlayer: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private var previousInterruptionFilter: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    fun start(): Boolean {
        synchronized(lock) {
            if (playing) return true

            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.e(TAG, "AudioManager no disponible")
                return false
            }

            val alarmAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val player: MediaPlayer?
            try {
                player = MediaPlayer.create(
                    appContext,
                    R.raw.sirena_emergencia,
                    alarmAttributes,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            } catch (t: Throwable) {
                Log.e(TAG, "MediaPlayer.create lanzó excepción", t)
                return false
            }

            if (player == null) {
                Log.e(TAG, "MediaPlayer.create devolvió null (¿falta R.raw.sirena_emergencia?)")
                return false
            }

            try {
                player.isLooping = true
                player.setVolume(1f, 1f)
                player.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer onError what=$what extra=$extra")
                    handlePlayerError()
                    true
                }
                player.start()
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo iniciar la reproducción", t)
                try { player.release() } catch (_: Throwable) {}
                return false
            }

            // A partir de aquí la sirena YA suena: aplicar efectos secundarios defensivamente.
            try {
                if (previousAlarmVolume == null) {
                    previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                }
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo forzar el volumen de alarma al máximo", t)
            }

            try {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    val current = nm.currentInterruptionFilter
                    if (current == NotificationManager.INTERRUPTION_FILTER_NONE) {
                        previousInterruptionFilter = current
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    }
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "Sin acceso a política DND", se)
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo ajustar DND", t)
            }

            try {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(alarmAttributes)
                    .setOnAudioFocusChangeListener { change ->
                        Log.d(TAG, "Cambio de foco de audio: $change")
                    }
                    .build()
                audioManager.requestAudioFocus(focusRequest)
                audioFocusRequest = focusRequest
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo solicitar foco de audio", t)
            }

            try {
                val v = obtainVibrator()
                vibrator = v
                if (v != null && v.hasVibrator()) {
                    val effect = VibrationEffect.createWaveform(longArrayOf(0L, 800L, 400L), 0)
                    v.vibrate(effect, alarmAttributes)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo iniciar la vibración continua", t)
            }

            mediaPlayer = player
            playing = true
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            val player = mediaPlayer
            mediaPlayer = null
            if (player != null) {
                try { player.stop() } catch (t: Throwable) { Log.w(TAG, "stop() MediaPlayer", t) }
                try { player.reset() } catch (t: Throwable) { Log.w(TAG, "reset() MediaPlayer", t) }
                try { player.release() } catch (t: Throwable) { Log.w(TAG, "release() MediaPlayer", t) }
            }

            try { vibrator?.cancel() } catch (t: Throwable) { Log.w(TAG, "cancel() vibración", t) }
            vibrator = null

            try {
                val saved = previousAlarmVolume
                if (saved != null) {
                    val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Restaurar volumen de alarma", t)
            }
            previousAlarmVolume = null

            try {
                val savedFilter = previousInterruptionFilter
                if (savedFilter != null) {
                    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (nm != null && nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(savedFilter)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Restaurar filtro DND", t)
            }
            previousInterruptionFilter = null

            try {
                val req = audioFocusRequest
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (req != null && am != null) {
                    am.abandonAudioFocusRequest(req)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Abandonar foco de audio", t)
            }
            audioFocusRequest = null

            playing = false
        }
    }

    fun isPlaying(): Boolean = playing

    private fun obtainVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun handlePlayerError() {
        synchronized(lock) {
            val player = mediaPlayer
            mediaPlayer = null
            if (player != null) {
                try { player.reset() } catch (_: Throwable) {}
                try { player.release() } catch (_: Throwable) {}
            }
            try { vibrator?.cancel() } catch (_: Throwable) {}
            vibrator = null
            playing = false
        }
    }
}
