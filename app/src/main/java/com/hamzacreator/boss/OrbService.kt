package com.hamzacreator.boss

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.util.Locale
import kotlin.math.min

/**
 * Floating Boss orb (WindowManager overlay). Tap it, speak in Urdu or
 * English — Boss routes the query: phone commands execute via the
 * accessibility service, everything else is answered conversationally
 * by Gemini and spoken back.
 *
 * Fixes vs Gemini's draft:
 *  - Toast.makeText(...).get() → .show()
 *  - removed unused LayoutInflater import
 *  - REAL foreground notification (channel + startForeground) with a
 *    "Band karo" stop action — MainActivity starts this via
 *    startForegroundService, so without this the app CRASHES on Android 8+.
 *  - Orb shows the Boss avatar (circular crop) instead of the mic icon.
 *  - processCommand passes the ORIGINAL voice query to the router
 *    (the draft parsed Gemini's reply instead).
 */
class OrbService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.hamzacreator.boss.STOP_ORB"
        private const val CHANNEL_ID = "boss_orb_channel"
        private const val NOTIF_ID = 1
    }

    private lateinit var windowManager: WindowManager
    private var orbView: ImageView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var geminiClient: GeminiClient

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        val prefs = getSharedPreferences("BossPrefs", MODE_PRIVATE)
        val apiKey = prefs.getString("GEMINI_API_KEY", "").orEmpty()
        geminiClient = GeminiClient(apiKey)
        geminiClient.setChatModel(prefs.getString("CHAT_MODEL", "gemini-3-flash-preview").orEmpty())
        tts = TextToSpeech(this, this)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val orb = ImageView(this)
        orb.setImageBitmap(circularAvatar())
        orb.contentDescription = "Boss"
        orbView = orb

        val params = WindowManager.LayoutParams(
            280, 280,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 400
        }
        orbParams = params
        orb.setOnTouchListener(OrbTouchListener())
        windowManager.addView(orb, params)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    /** Circular-cropped Boss avatar for the orb. */
    private fun circularAvatar(): Bitmap {
        val src = BitmapFactory.decodeResource(resources, R.drawable.boss_avatar)
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 280, 280, true)
        val out = Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Circular mask via destination-in.
        canvas.drawCircle(140f, 140f, 140f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }

    private inner class OrbTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = orbParams?.x ?: 0
                    startY = orbParams?.y ?: 0
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    orbParams?.let {
                        it.x = startX + dx
                        it.y = startY + dy
                        windowManager.updateViewLayout(orbView, it)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) startListening()
                    return true
                }
            }
            return false
        }
    }

    private fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Toast.makeText(this, "Voice recognition available nahi hai.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Toast.makeText(applicationContext, "Sun nahi saka, dobara try karein.", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) processCommand(matches[0])
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Mic start nahi ho saka.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Routes the ORIGINAL voice query (not Gemini's reply). */
    private fun processCommand(query: String) {
        val ui = object : CommandRouter.UiCallbacks {
            override fun onStreamStart() {}
            override fun onStreamChunk(chunk: String) {}
            override fun onFinal(text: String) {
                try {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boss_orb")
                } catch (_: Exception) {
                }
            }
            override fun onError(text: String) {
                try {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boss_orb")
                } catch (_: Exception) {
                }
            }
        }
        CommandRouter.handle(query, geminiClient, tts, ui)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale("ur", "PK")
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        try {
            orbView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        orbView = null
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Boss orb", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OrbService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Boss chal raha hai")
            .setContentText("Orb dabao aur bolo")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
