package com.hamzacreator.boss

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.hamzacreator.boss.databinding.ActivityMainBinding
import com.hamzacreator.boss.databinding.ItemSessionBinding
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Boss home v1.1 — Muse-style simple UI:
 * top bar (avatar + Boss + online dot + gear), chat bubbles with a
 * working indicator, big mic button, bottom nav (Chat/Video/Camera),
 * and a drawer with chat sessions + orb switch.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var sessionAdapter: SessionAdapter
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val prefs by lazy { getSharedPreferences("BossPrefs", MODE_PRIVATE) }
    private var currentSessionId: String = ChatSessions.newId()
    private var orbGuard = false

    // Continuous voice conversation mode (🌊 button).
    private var continuousVoice = false
    private var ttsSpeaking = false
    private var voiceListening = false

    private val veoModels = listOf(
        "veo-3.1-generate-preview",
        "veo-3.1-fast-generate-preview",
        "veo-3.1-lite-generate-preview",
        "Gemini Omni",
        "Custom…"
    )

    // ---------- Activity result launchers ----------

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                binding.tvPickedVideo.text = "Video mil gayi ✓"
                binding.videoPreview.visibility = View.VISIBLE
                binding.videoPreview.setVideoURI(uri)
                binding.videoPreview.start()
            }
        }

    private val recordVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val uri = res.data?.data
                if (uri != null) {
                    val saved = copyVideoToGallery(uri)
                    binding.tvCameraStatus.text =
                        if (saved != null) "Video save ho gayi ✓ (Movies/Boss)"
                        else "Video bani lekin save nahi ho saki."
                } else binding.tvCameraStatus.text = "Video nahi mili."
            }
        }

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val bmp = res.data?.extras?.get("data") as? Bitmap
                if (bmp != null) {
                    val saved = savePhotoToGallery(bmp)
                    binding.imgCameraThumb.visibility = View.VISIBLE
                    binding.imgCameraThumb.setImageBitmap(bmp)
                    binding.tvCameraStatus.text =
                        if (saved) "Photo save ho gayi ✓ (Pictures/Boss)"
                        else "Photo bani lekin save nahi ho saki."
                } else binding.tvCameraStatus.text = "Photo nahi mili."
            }
        }

    /** Google sign-in straight from the home top bar (PRO badge tap). */
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                try {
                    GoogleSignIn.getSignedInAccountFromIntent(res.data).result
                    toast("Sign in ho gaya! ✨ PRO active")
                } catch (_: Exception) {
                    toast("Sign in nahi ho saka — dobara try karein.")
                }
            } else {
                toast("Sign in cancel ho gaya.")
            }
            refreshIdentity()
        }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First run → onboarding.
        if (!prefs.getBoolean("ONBOARDED", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)

        setupTopBar()
        setupDrawer()
        setupBottomNav()
        setupChat()
        setupVideo()
        setupCamera()
        showSection(0)
    }

    override fun onResume() {
        super.onResume()
        refreshSessions()
        refreshOrbSwitch()
        refreshIdentity()
    }

    override fun onPause() {
        saveCurrentSession()
        super.onPause()
    }

    // ---------- Top bar & drawer ----------

    private fun setupTopBar() {
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        // Tapping the PRO badge / profile area signs in with Google (or opens Settings when signed in).
        val proTap = View.OnClickListener {
            if (GoogleAuth.isSignedIn(this)) openSettings()
            else {
                try {
                    googleSignInLauncher.launch(GoogleAuth.client(this).signInIntent)
                } catch (_: Exception) {
                    toast("Google sign-in nahi khul saka — Play Services check karein.")
                }
            }
        }
        binding.tvProBadge.setOnClickListener(proTap)
        binding.cardUserPhoto.setOnClickListener(proTap)
        // When signed out the badge is hidden — show a tappable "Sign in" pill instead.
        binding.tvProBadge.isClickable = true
        binding.tvProBadge.isFocusable = true
    }

    private fun openSettings() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun setupDrawer() {
        sessionAdapter = SessionAdapter(
            onOpen = { s ->
                saveCurrentSession()
                currentSessionId = s.id
                chatAdapter.setMessages(s.messages)
                binding.rvChat.scrollToPosition(maxOf(0, chatAdapter.itemCount - 1))
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                showSection(0)
                updateHeroVisibility()
            },
            onDelete = { s ->
                ChatSessions.delete(this, s.id)
                if (s.id == currentSessionId) newChat()
                else refreshSessions()
            }
        )
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = sessionAdapter

        binding.btnNewChat.setOnClickListener {
            saveCurrentSession()
            newChat()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnSettingsDrawer.setOnClickListener { openSettings() }

        binding.switchOrb.setOnCheckedChangeListener { _, isChecked ->
            if (orbGuard) return@setOnCheckedChangeListener
            toggleOrb(isChecked)
        }
    }

    private fun refreshSessions() {
        if (!::sessionAdapter.isInitialized) return
        sessionAdapter.setSessions(ChatSessions.list(this))
    }

    private fun newChat() {
        currentSessionId = ChatSessions.newId()
        val name = GoogleAuth.displayName(this)
        val greet = if (name.isNotEmpty()) {
            "Assalam-o-Alaikum, $name! 👋 Main Boss hoon. Kuch poochein, ya bolein — jaise \"WhatsApp kholo\"."
        } else {
            "Assalam-o-Alaikum! Main Boss hoon. Kuch poochein, ya bolein — jaise \"WhatsApp kholo\"."
        }
        chatAdapter.setMessages(listOf(ChatMessage(greet, false)))
        // Fresh chat → show the Gemini-style hero.
        binding.heroView.visibility = View.VISIBLE
    }

    private fun saveCurrentSession() {
        if (!::chatAdapter.isInitialized) return
        val msgs = chatAdapter.getMessages()
        if (msgs.any { it.isUser }) {
            ChatSessions.save(
                this,
                ChatSession(
                    currentSessionId,
                    ChatSessions.titleFor(msgs),
                    msgs,
                    System.currentTimeMillis()
                )
            )
        }
    }

    // ---------- Orb ON/OFF ----------

    private fun refreshOrbSwitch() {
        if (!::binding.isInitialized) return
        orbGuard = true
        binding.switchOrb.isChecked = OrbService.isRunning
        orbGuard = false
    }

    /** Shows Google profile photo + name + ✨ PRO badge when signed in. */
    private fun refreshIdentity() {
        if (!::binding.isInitialized) return
        if (GoogleAuth.isSignedIn(this)) {
            val name = GoogleAuth.displayName(this)
            binding.tvProBadge.visibility = View.VISIBLE
            if (name.isNotEmpty()) {
                binding.tvUserName.text = name
                binding.tvUserName.visibility = View.VISIBLE
            } else {
                binding.tvUserName.visibility = View.GONE
            }
            binding.cardUserPhoto.visibility = View.VISIBLE
            GoogleAuth.loadPhotoBitmap(this) { bmp ->
                if (bmp != null) binding.imgUserPhoto.setImageBitmap(bmp)
            }
        } else {
            // Signed out: show a tappable "Sign in" pill so the button is never dead.
            binding.tvProBadge.text = "🔵 Sign in"
            binding.tvProBadge.visibility = View.VISIBLE
            binding.tvUserName.visibility = View.GONE
            binding.cardUserPhoto.visibility = View.GONE
        }
    }

    private fun toggleOrb(wantOn: Boolean) {
        if (wantOn) {
            val apiKey = prefs.getString("GEMINI_API_KEY", "").orEmpty()
            if (apiKey.isEmpty()) {
                toast("Pehle Settings mein Gemini API Key dalein.")
                refreshOrbSwitch()
                openSettings()
                return
            }
            if (!BossAccessibilityService.isInstanceActive()) {
                refreshOrbSwitch()
                AlertDialog.Builder(this)
                    .setTitle("Accessibility chahiye")
                    .setMessage("Phone control ke liye pehle Accessibility mein 'Boss' ko ON karein.")
                    .setPositiveButton("Settings kholo") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                toast("Pehle 'Display over other apps' permission dein.")
                refreshOrbSwitch()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return
            }
            startForegroundService(Intent(this, OrbService::class.java))
            toast("🔮 Voice Orb ON — kahin bhi tap karke bolein!")
        } else {
            stopService(Intent(this, OrbService::class.java))
            toast("Voice Orb OFF")
        }
        refreshOrbSwitch()
    }

    // ---------- Bottom nav ----------

    private fun setupBottomNav() {
        binding.navChat.setOnClickListener { showSection(0) }
        binding.navVideo.setOnClickListener { showSection(1) }
        binding.navCamera.setOnClickListener { showSection(2) }
    }

    private fun showSection(idx: Int) {
        binding.sectionChat.visibility = if (idx == 0) View.VISIBLE else View.GONE
        binding.sectionVideo.visibility = if (idx == 1) View.VISIBLE else View.GONE
        binding.sectionCamera.visibility = if (idx == 2) View.VISIBLE else View.GONE
        // Input bar only makes sense on the chat tab.
        binding.inputBar.visibility = if (idx == 0) View.VISIBLE else View.GONE
        if (idx == 0) updateHeroVisibility()
        else binding.heroView.visibility = View.GONE
        // Pill highlight.
        binding.navChat.setBackgroundResource(if (idx == 0) R.drawable.pill_active else R.drawable.pill_bg)
        binding.navVideo.setBackgroundResource(if (idx == 1) R.drawable.pill_active else R.drawable.pill_bg)
        binding.navCamera.setBackgroundResource(if (idx == 2) R.drawable.pill_active else R.drawable.pill_bg)
        val active = getColor(android.R.color.white)
        val dim = getColor(R.color.boss_light)
        binding.navChatLabel.setTextColor(if (idx == 0) active else dim)
        binding.navVideoLabel.setTextColor(if (idx == 1) active else dim)
        binding.navCameraLabel.setTextColor(if (idx == 2) active else dim)
    }

    /** Hero shows only when the chat has no user messages yet (Gemini-style empty state). */
    private fun updateHeroVisibility() {
        val hasUserMsg = ::chatAdapter.isInitialized && chatAdapter.getMessages().any { it.isUser }
        binding.heroView.visibility = if (hasUserMsg) View.GONE else View.VISIBLE
    }

    private fun fadeHeroOut() {
        if (binding.heroView.visibility != View.VISIBLE) return
        binding.heroView.animate().alpha(0f).setDuration(250).withEndAction {
            binding.heroView.visibility = View.GONE
            binding.heroView.alpha = 1f
        }.start()
    }

    // ---------- Chat ----------

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.adapter = chatAdapter
        // Restore last session, else greeting.
        val last = ChatSessions.list(this).firstOrNull()
        if (last != null && last.messages.isNotEmpty()) {
            currentSessionId = last.id
            chatAdapter.setMessages(last.messages)
        } else {
            newChat()
        }
        updateHeroVisibility()

        // Gemini-style input bar: [+] attach | text field | 🎤 one-shot voice | 🌊/➤ voice-or-send.
        binding.btnAttach.setOnClickListener {
            showSection(1)
            pickVideoLauncher.launch("video/*")
        }
        binding.btnMic.setOnClickListener { startVoiceInput() }
        binding.btnVoice.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etMessage.text.clear()
                sendMessage(text)
            } else {
                toggleContinuousVoice()
            }
        }
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateVoiceButton() }
        })
        updateVoiceButton()

        binding.btnHeroStart.setOnClickListener {
            binding.etMessage.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etMessage, 0)
        }
    }

    /** 🌊 shows when the field is empty (continuous voice); ➤ shows when there is text (send). */
    private fun updateVoiceButton() {
        val hasText = ::binding.isInitialized &&
            binding.etMessage.text.toString().trim().isNotEmpty()
        binding.btnVoice.text = if (hasText) "➤" else "🌊"
        binding.btnVoice.alpha = if (continuousVoice && !hasText) 1f else 0.85f
    }

    /** Toggles continuous voice conversation: listen → reply (TTS) → listen again. */
    private fun toggleContinuousVoice() {
        continuousVoice = !continuousVoice
        if (continuousVoice) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { ttsSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    ttsSpeaking = false
                    runOnUiThread { if (continuousVoice) startVoiceInput() }
                }
                override fun onError(utteranceId: String?) {
                    ttsSpeaking = false
                    runOnUiThread { if (continuousVoice) startVoiceInput() }
                }
            })
            toast("🌊 Continuous voice ON — bolein! Rukne ke liye dobara dabayein.")
            startVoiceInput()
        } else {
            ttsSpeaking = false
            toast("🌊 Continuous voice OFF")
        }
        updateVoiceButton()
    }

    private fun currentGemini(): GeminiClient {
        val key = prefs.getString("GEMINI_API_KEY", "").orEmpty()
        val model = prefs.getString("CHAT_MODEL", "gemini-3-flash-preview").orEmpty()
            .ifEmpty { "gemini-3-flash-preview" }
        return GeminiClient(key).also { it.setChatModel(model) }
    }

    private fun sendMessage(text: String) {
        val userPos = chatAdapter.addMessage(ChatMessage(text, true))
        fadeHeroOut()
        binding.rvChat.scrollToPosition(userPos)
        var streamPos = -1

        val ui = object : CommandRouter.UiCallbacks {
            override fun onWorking(icon: String, text: String) {
                showWorking(icon, text)
            }
            override fun onStreamStart() {
                hideWorking()
                streamPos = chatAdapter.addMessage(ChatMessage("", false))
                binding.rvChat.scrollToPosition(streamPos)
            }
            override fun onStreamChunk(chunk: String) {
                if (streamPos >= 0) {
                    chatAdapter.appendToMessage(streamPos, chunk)
                    binding.rvChat.scrollToPosition(streamPos)
                }
            }
            override fun onFinal(text: String) {
                hideWorking()
                if (streamPos >= 0) {
                    chatAdapter.updateMessage(streamPos, text)
                    binding.rvChat.scrollToPosition(streamPos)
                } else {
                    val p = chatAdapter.addMessage(ChatMessage(text, false))
                    binding.rvChat.scrollToPosition(p)
                }
                saveCurrentSession()
                if (continuousVoice) {
                    // Safety net: if TTS never spoke (no utterance callbacks), keep listening.
                    binding.root.postDelayed({
                        if (continuousVoice && !ttsSpeaking && !voiceListening) startVoiceInput()
                    }, 2500)
                }
            }
            override fun onError(text: String) {
                hideWorking()
                if (continuousVoice) {
                    continuousVoice = false
                    updateVoiceButton()
                }
                val p = chatAdapter.addMessage(ChatMessage("⚠️ $text", false))
                binding.rvChat.scrollToPosition(p)
            }
        }
        CommandRouter.handle(text, currentGemini(), tts, ui)
    }

    private fun showWorking(icon: String, text: String) {
        binding.workingIcon.text = icon
        binding.workingText.text = text
        binding.workingCard.visibility = View.VISIBLE
    }

    private fun hideWorking() {
        binding.workingCard.visibility = View.GONE
    }

    // ---------- Voice input (reliable) ----------

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val denied = prefs.getInt("MIC_DENIED_COUNT", 0)
            if (denied >= 1) {
                // Explain clearly before asking again.
                AlertDialog.Builder(this)
                    .setTitle("🎤 Mic permission")
                    .setMessage("Bolein sun-ne ke liye mic permission chahiye. 'Allow' dabayein.")
                    .setPositiveButton("Allow") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.RECORD_AUDIO), 101
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 101
                )
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            chatAdapter.addMessage(ChatMessage("⚠️ Is phone par voice recognition available nahi hai.", false))
            return
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                voiceListening = true
                showWorking("🎤", "Sun raha hoon... bolein!")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                hideWorking()
                voiceListening = false
                if (continuousVoice &&
                    (e == SpeechRecognizer.ERROR_NO_MATCH ||
                        e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    // User stayed silent — keep the conversation loop going quietly.
                    startVoiceInput()
                    return
                }
                if (continuousVoice) {
                    continuousVoice = false
                    updateVoiceButton()
                }
                val msg = if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    "⚠️ Mic permission nahi mili — Settings → Apps → Boss → Permissions mein Mic ON karein."
                } else {
                    "⚠️ Sun nahi saka — dobara koshish karein."
                }
                val p = chatAdapter.addMessage(ChatMessage(msg, false))
                binding.rvChat.scrollToPosition(p)
            }
            override fun onResults(r: Bundle?) {
                hideWorking()
                voiceListening = false
                val matches = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) sendMessage(matches[0])
                else if (continuousVoice) startVoiceInput()
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            hideWorking()
            chatAdapter.addMessage(ChatMessage("⚠️ Mic start nahi ho saka.", false))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putInt("MIC_DENIED_COUNT", 0).apply()
                toast("Mic OK ✓ — ab bolein!")
            } else {
                val n = prefs.getInt("MIC_DENIED_COUNT", 0) + 1
                prefs.edit().putInt("MIC_DENIED_COUNT", n).apply()
                if (n >= 2) {
                    AlertDialog.Builder(this)
                        .setTitle("🎤 Mic band hai")
                        .setMessage("Voice ke liye: Settings → Apps → Boss → Permissions → Microphone ON karein.")
                        .setPositiveButton("Settings kholo") { _, _ ->
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    toast("Mic permission ke baghair voice nahi chalegi.")
                }
            }
        }
    }

    // ---------- Video tab ----------

    private fun setupVideo() {
        binding.btnPickVideo.setOnClickListener { pickVideoLauncher.launch("video/*") }
        binding.btnGenerate.setOnClickListener { onGenerateVideo() }
        setupVeoSpinner()
    }

    private fun setupVeoSpinner() {
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, veoModels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVeoModel.adapter = adapter
        val saved = prefs.getString("VEO_MODEL", veoModels[0]).orEmpty()
        val pos = veoModels.indexOf(saved).takeIf { it >= 0 } ?: 4
        binding.spinnerVeoModel.setSelection(if (saved in veoModels) pos else 4)
        if (saved !in veoModels && saved.isNotEmpty()) binding.etCustomVeoModel.setText(saved)
        binding.spinnerVeoModel.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long
                ) {
                    binding.etCustomVeoModel.visibility =
                        if (veoModels[position] == "Custom…") View.VISIBLE else View.GONE
                    prefs.edit().putString("VEO_MODEL", currentVeoModel()).apply()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        binding.etCustomVeoModel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString("VEO_MODEL", currentVeoModel()).apply()
        }
    }

    private fun currentVeoModel(): String {
        val pos = binding.spinnerVeoModel.selectedItemPosition
        return if (veoModels.getOrNull(pos) == "Custom…") {
            binding.etCustomVeoModel.text.toString().trim().ifEmpty { veoModels[0] }
        } else {
            veoModels.getOrNull(pos) ?: veoModels[0]
        }
    }

    private fun onGenerateVideo() {
        val prompt = binding.etVideoPrompt.text.toString().trim()
        if (prompt.isEmpty()) {
            toast("Pehle video prompt likhein.")
            return
        }
        val apiKey = prefs.getString("GEMINI_API_KEY", "").orEmpty()
        if (apiKey.isEmpty()) {
            toast("Pehle Settings mein Gemini API Key dalein.")
            openSettings()
            return
        }
        val veoModel = currentVeoModel()
        prefs.edit().putString("VEO_MODEL", veoModel).apply()

        val aspect = if (binding.rb169.isChecked) "16:9" else "9:16"
        val duration = when (binding.rgDuration.checkedRadioButtonId) {
            R.id.rb30s -> 30
            R.id.rb60s -> 60
            R.id.rb90s -> 90
            else -> 15
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102
            )
            return
        }

        binding.btnGenerate.isEnabled = false
        binding.progressBarVideo.visibility = View.VISIBLE
        binding.tvVideoProgress.text = "Shuru ho raha hai..."
        binding.videoView.visibility = View.GONE
        showWorking("🎥", "Video ban rahi hai...")

        val workDir = File(cacheDir, "veo_clips")
        VideoGenerator(apiKey).generate(
            prompt, aspect, duration, veoModel, workDir,
            object : VideoGenerator.Listener {
                override fun onProgress(text: String) {
                    binding.tvVideoProgress.text = text
                    showWorking("🎥", text)
                }
                override fun onComplete(finalFile: File) {
                    hideWorking()
                    binding.btnGenerate.isEnabled = true
                    binding.progressBarVideo.visibility = View.GONE
                    val uri = saveVideoToGallery(finalFile)
                    try {
                        finalFile.delete()
                    } catch (_: Exception) {
                    }
                    if (uri != null) {
                        binding.tvVideoProgress.text = "Video taiyar! Movies/Boss mein save ho gayi ✓"
                        binding.videoView.visibility = View.VISIBLE
                        binding.videoView.setVideoURI(uri)
                        binding.videoView.start()
                    } else {
                        binding.tvVideoProgress.text = "Video bani lekin save nahi ho saki."
                    }
                }
                override fun onError(message: String) {
                    hideWorking()
                    binding.btnGenerate.isEnabled = true
                    binding.progressBarVideo.visibility = View.GONE
                    binding.tvVideoProgress.text = message
                }
            }
        )
    }

    private fun saveVideoToGallery(file: File): Uri? {
        val name = "boss_video_${System.currentTimeMillis()}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Boss")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Boss"
                )
                dir.mkdirs()
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = true)
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
                Uri.fromFile(dest)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Camera tab ----------

    private fun setupCamera() {
        binding.btnRecordVideo.setOnClickListener {
            if (ensureCameraPermission()) {
                recordVideoLauncher.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
            }
        }
        binding.btnTakePhoto.setOnClickListener {
            if (ensureCameraPermission()) {
                takePhotoLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            }
        }
    }

    private fun ensureCameraPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 103)
            toast("Camera permission dein, phir dobara dabayein.")
            false
        } else true
    }

    private fun copyVideoToGallery(src: Uri): Uri? {
        val name = "boss_video_${System.currentTimeMillis()}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Boss")
                }
                val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openInputStream(src)?.use { inp ->
                    contentResolver.openOutputStream(dest)?.use { out -> inp.copyTo(out) }
                }
                dest
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Boss"
                )
                dir.mkdirs()
                val dest = File(dir, name)
                contentResolver.openInputStream(src)?.use { inp ->
                    dest.outputStream().use { out -> inp.copyTo(out) }
                }
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
                Uri.fromFile(dest)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun savePhotoToGallery(bmp: Bitmap): Boolean {
        val name = "boss_photo_${System.currentTimeMillis()}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Boss")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                true
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Boss"
                )
                dir.mkdirs()
                val dest = File(dir, name)
                dest.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- TTS ----------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale("ur", "PK")
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        continuousVoice = false
        saveCurrentSession()
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Sessions drawer adapter ----------

    private inner class SessionAdapter(
        private val onOpen: (ChatSession) -> Unit,
        private val onDelete: (ChatSession) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<ChatSession> = emptyList()

        inner class VH(val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root)

        fun setSessions(list: List<ChatSession>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.b.tvSessionTitle.text = s.title
            holder.b.root.setOnClickListener { onOpen(s) }
            holder.b.btnDeleteSession.setOnClickListener { onDelete(s) }
        }

        override fun getItemCount(): Int = items.size
    }
}
