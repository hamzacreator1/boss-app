package com.hamzacreator.boss

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Branded splash intro — deliberately ULTRA-SAFE.
 *
 * Yesterday's v4 crashed on launch and the splash was the prime suspect,
 * so this activity uses ONLY a plain ImageView + TextViews in a simple
 * LinearLayout: no custom views, no view-inflation tricks, no audio.
 * EVERYTHING is wrapped in try/catch — if anything at all goes wrong,
 * we still fall through to MainActivity. Never a black screen, never
 * a crash loop.
 */
class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var movedOn = false

    private val goToMain = Runnable {
        try {
            if (!movedOn) {
                movedOn = true
                startActivity(Intent(this, MainActivity::class.java))
            }
        } catch (_: Exception) {
            // Even startActivity is guarded; last resort below.
        } finally {
            try {
                finish()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_splash)
        } catch (_: Exception) {
            // If even the layout fails, skip straight to MainActivity.
            goToMain.run()
            return
        }
        try {
            // ~1.5 seconds of branding, then MainActivity — always.
            handler.postDelayed(goToMain, 1500)
        } catch (_: Exception) {
            goToMain.run()
        }
    }

    override fun onDestroy() {
        try {
            handler.removeCallbacks(goToMain)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
