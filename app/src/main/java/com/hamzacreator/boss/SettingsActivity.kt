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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.hamzacreator.boss.databinding.ActivitySettingsBinding

/**
 * All the "clutter" lives here: API key, model pickers,
 * and a permissions checklist with a "Theek karo" button per row.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("BossPrefs", MODE_PRIVATE) }
    private val chatModels = listOf("gemini-3-flash-preview", "gemini-flash-latest", "Custom…")

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                try {
                    GoogleSignIn.getSignedInAccountFromIntent(res.data).result
                } catch (_: Exception) {
                }
            }
            refreshProRow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etApiKeyS.setText(prefs.getString("GEMINI_API_KEY", ""))
        binding.etVeoModelS.setText(prefs.getString("VEO_MODEL", "veo-3.1-generate-preview"))

        setupModelSpinner()

        binding.btnFixApiKey.setOnClickListener {
            binding.etApiKeyS.requestFocus()
        }
        binding.btnFixA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnFixMic.setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        binding.btnFixOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }

        binding.btnGoogleSignInS.setOnClickListener {
            googleSignInLauncher.launch(GoogleAuth.client(this).signInIntent)
        }
        binding.btnSignOut.setOnClickListener {
            GoogleAuth.signOut(this) { refreshProRow() }
        }
    }

    private fun refreshProRow() {
        if (GoogleAuth.isSignedIn(this)) {
            val name = GoogleAuth.displayName(this).ifEmpty { "Dost" }
            binding.tvProStatus.text = "✨ PRO active — khush aamdeed, $name!"
            binding.tvProStatus.setTextColor(
                android.graphics.Color.parseColor("#FFD54F")
            )
            binding.btnGoogleSignInS.visibility = View.GONE
            binding.btnSignOut.visibility = View.VISIBLE
        } else {
            binding.tvProStatus.text = "Pro features ke liye Google se sign in karein 🙂"
            binding.tvProStatus.setTextColor(
                android.graphics.Color.parseColor("#8A94B0")
            )
            binding.btnGoogleSignInS.visibility = View.VISIBLE
            binding.btnSignOut.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProRow()
        // Persist edits whenever returning.
        prefs.edit()
            .putString("GEMINI_API_KEY", binding.etApiKeyS.text.toString().trim())
            .putString("VEO_MODEL", binding.etVeoModelS.text.toString().trim().ifEmpty { "veo-3.1-generate-preview" })
            .putString("CHAT_MODEL", currentChatModel())
            .apply()
        refreshChecklist()
    }

    override fun onPause() {
        prefs.edit()
            .putString("GEMINI_API_KEY", binding.etApiKeyS.text.toString().trim())
            .putString("VEO_MODEL", binding.etVeoModelS.text.toString().trim().ifEmpty { "veo-3.1-generate-preview" })
            .putString("CHAT_MODEL", currentChatModel())
            .apply()
        super.onPause()
    }

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, chatModels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerChatModelS.adapter = adapter
        val saved = prefs.getString("CHAT_MODEL", chatModels[0]).orEmpty()
        val pos = chatModels.indexOf(saved).takeIf { it >= 0 } ?: 2
        binding.spinnerChatModelS.setSelection(if (saved in chatModels) pos else 2)
        if (saved !in chatModels && saved.isNotEmpty()) binding.etCustomModelS.setText(saved)
        binding.spinnerChatModelS.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                binding.etCustomModelS.visibility =
                    if (chatModels[position] == "Custom…") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun currentChatModel(): String {
        val pos = binding.spinnerChatModelS.selectedItemPosition
        return if (chatModels.getOrNull(pos) == "Custom…") {
            binding.etCustomModelS.text.toString().trim().ifEmpty { chatModels[0] }
        } else {
            chatModels.getOrNull(pos) ?: chatModels[0]
        }
    }

    private fun refreshChecklist() {
        setRow(
            binding.tvStatusApiKey,
            prefs.getString("GEMINI_API_KEY", "").orEmpty().isNotEmpty()
        )
        setRow(binding.tvStatusA11y, BossAccessibilityService.isInstanceActive())
        setRow(
            binding.tvStatusMic,
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        setRow(
            binding.tvStatusOverlay,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        )
    }

    private fun setRow(tv: android.widget.TextView, ok: Boolean) {
        tv.text = if (ok) "✓ OK" else "✗ Missing"
        tv.setTextColor(
            if (ok) android.graphics.Color.parseColor("#7CFC9A")
            else android.graphics.Color.parseColor("#FF8A8A")
        )
    }
}
