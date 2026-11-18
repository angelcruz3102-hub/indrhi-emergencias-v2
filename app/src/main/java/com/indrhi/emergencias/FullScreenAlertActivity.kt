package com.indrhi.emergencias

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class FullScreenAlertActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Encender pantalla y mostrarse encima del bloqueo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent?.getStringExtra(SirenForegroundService.EXTRA_TITLE)
            ?: "ALERTA DE EMERGENCIA INDRHI"
        val body = intent?.getStringExtra(SirenForegroundService.EXTRA_BODY)
            ?: "Evacúe el edificio inmediatamente."

        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setPadding(pad, pad, pad, pad)
        }

        val badge = TextView(this).apply {
            text = "EMERGENCIA ACTIVA"
            setTextColor(Color.parseColor("#FFCDD2"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (12 * dp).toInt())
        }

        val bodyView = TextView(this).apply {
            text = body
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (32 * dp).toInt())
        }

        val btnStop = Button(this).apply {
            text = "DETENER SIRENA"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.parseColor("#B71C1C"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = (68 * dp).toInt()
            setOnClickListener {
                SirenForegroundService.stopSiren(this@FullScreenAlertActivity)
                val mainIntent = Intent(this@FullScreenAlertActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(mainIntent)
                finish()
            }
        }

        val btnPanel = Button(this).apply {
            text = "Ir al panel de reporte"
            setBackgroundColor(Color.parseColor("#7F0000"))
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = (54 * dp).toInt()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (14 * dp).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                val mainIntent = Intent(this@FullScreenAlertActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(mainIntent)
                finish()
            }
        }

        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        root.addView(badge)
        root.addView(titleView)
        root.addView(bodyView)
        root.addView(btnStop, btnLp)
        root.addView(btnPanel)

        setContentView(root)

        // Evitar que el botón Atrás cierre la alerta por accidente sin silenciarla
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // No cerrar hasta que el usuario pulse "Detener Sirena" o "Ir al panel"
                }
            }
        )
    }
}
