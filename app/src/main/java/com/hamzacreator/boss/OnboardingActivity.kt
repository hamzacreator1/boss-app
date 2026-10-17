package com.hamzacreator.boss

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.hamzacreator.boss.databinding.ActivityOnboardingBinding

/**
 * First-run onboarding: 3 friendly steps.
 * 1: API key → 2: permissions → 3: done.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val prefs by lazy { getSharedPreferences("BossPrefs", MODE_PRIVATE) }

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(res.data)
                    .getResult(com.google.android.gms.common.api.ApiException::class.java)
                val name = account?.displayName.orEmpty().ifEmpty { "Dost" }
                Toast.makeText(this, "✨ PRO active! Khush aamdeed, $name!", Toast.LENGTH_LONG).show()
            } catch (e: com.google.android.gms.common.api.ApiException) {
                val msg = when (e.statusCode) {
                    12501 -> "Sign in cancel ho gaya."
                    12500 -> "Sign in fail ho gaya (12500) — Google Play Services update karke dobara try karein."
                    7 -> "Internet nahi mil raha — net on karke dobara try karein."
                    else -> "Sign in mein masla (code ${e.statusCode}) — dobara try karein."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Sign in nahi ho saka.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etApiKeyO.setText(prefs.getString("GEMINI_API_KEY", ""))

        binding.btnNext1.setOnClickListener {
            val key = binding.etApiKeyO.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Pehle API key dalein 🙂", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("GEMINI_API_KEY", key).apply()
            showStep(2)
        }

        binding.btnPermA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "List mein 'Boss' dhoondh kar ON karein", Toast.LENGTH_LONG).show()
        }
        binding.btnPermMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 101
                )
            } else {
                Toast.makeText(this, "Mic permission pehle se hai ✓", Toast.LENGTH_SHORT).show()
            }
            refreshPermStatus()
        }
        binding.btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                Toast.makeText(this, "Overlay permission pehle se hai ✓", Toast.LENGTH_SHORT).show()
            }
            refreshPermStatus()
        }
        binding.btnNext2.setOnClickListener { showStep(3) }

        binding.btnGoogleSignInO.setOnClickListener {
            googleSignInLauncher.launch(GoogleAuth.client(this).signInIntent)
        }

        binding.btnStart.setOnClickListener {
            prefs.edit().putBoolean("ONBOARDED", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        showStep(1)
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
    }

    private fun showStep(n: Int) {
        binding.step1.visibility = if (n == 1) View.VISIBLE else View.GONE
        binding.step2.visibility = if (n == 2) View.VISIBLE else View.GONE
        binding.step3.visibility = if (n == 3) View.VISIBLE else View.GONE
        binding.tvSteps.text = when (n) {
            1 -> "● ○ ○"
            2 -> "○ ● ○"
            else -> "○ ○ ●"
        }
        if (n == 2) refreshPermStatus()
    }

    private fun refreshPermStatus() {
        val a11y = BossAccessibilityService.isInstanceActive()
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val done = listOf(a11y, mic, overlay).count { it }
        binding.tvPermStatus.text = if (done == 3) "Teeno permissions OK! ✓" else "$done/3 permissions OK"
    }
}
