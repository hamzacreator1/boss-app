package com.hamzacreator.boss

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hamzacreator.boss.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Boss home: Chat tab (conversational AI + phone commands) and
 * Video tab (Veo AI video generation with auto-chaining), plus the
 * big BOSS ON/OFF toggle for the floating voice orb.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val chatModels = listOf("gemini-3-flash-preview", "gemini-flash-latest", "Custom…")
    private val defaultVeoModel = "veo-3.1-generate-preview"

    private val prefs by lazy { getSharedPreferences("BossPrefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        binding.etApiKey.setText(prefs.getString("GEMINI_API_KEY", ""))
        binding.etVeoModel.setText(prefs.getString("VEO_MODEL", defaultVeoModel))

        setupTabs()
        setupChat()
        setupModelSpinner()
        setupToggle()
        setupVideo()

        // Mic needs this; ask once up front.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    // ---------------- Tabs ----------------

    private fun setupTabs() {
        binding.btnTabChat.setOnClickListener { showTab(true) }
        binding.btnTabVideo.setOnClickListener { showTab(false) }
        showTab(true)
    }

    private fun showTab(chat: Boolean) {
        binding.layoutChatSection.visibility = if (chat) View.VISIBLE else View.GONE
        binding.layoutVideoSection.visibility = if (chat) View.GONE else View.VISIBLE
    }

    // ---------------- Chat ----------------

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.adapter = chatAdapter
        chatAdapter.addMessage(
            ChatMessage("Assalam-o-Alaikum! Main Boss hoon. Kuch poochein, ya bolein — jaise \"WhatsApp kholo\".", false)
        )

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etMessage.text.clear()
                sendMessage(text)
            }
        }
        binding.btnMic.setOnClickListener { startVoiceInput() }
    }

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, chatModels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerChatModel.adapter = adapter
        val saved = prefs.getString("CHAT_MODEL", chatModels[0])
        val idx = chatModels.indexOf(saved).takeIf { it >= 0 } ?: 2
        binding.spinnerChatModel.setSelection(if (saved in chatModels) idx else 2)
        if (saved !in chatModels) binding.etCustomModel.setText(saved)

        binding.spinnerChatModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val custom = chatModels[pos] == "Custom…"
                binding.etCustomModel.visibility = if (custom) View.VISIBLE else View.GONE
                persistChatModel()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.etCustomModel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistChatModel()
        }
    }

    private fun currentChatModel(): String {
        val pos = binding.spinnerChatModel.selectedItemPosition
        return if (chatModels.getOrNull(pos) == "Custom…") {
            binding.etCustomModel.text.toString().trim().ifEmpty { chatModels[0] }
        } else {
            chatModels.getOrNull(pos) ?: chatModels[0]
        }
    }

    private fun persistChatModel() {
        prefs.edit().putString("CHAT_MODEL", currentChatModel()).apply()
    }

    private fun currentGemini(): GeminiClient {
        val key = binding.etApiKey.text.toString().trim()
            .ifEmpty { prefs.getString("GEMINI_API_KEY", "").orEmpty() }
        return GeminiClient(key).also { it.setChatModel(currentChatModel()) }
    }

    private fun sendMessage(text: String) {
        val userPos = chatAdapter.addMessage(ChatMessage(text, true))
        binding.rvChat.scrollToPosition(userPos)
        var streamPos = -1
        val full = StringBuilder()

        val ui = object : CommandRouter.UiCallbacks {
            override fun onStreamStart() {
                streamPos = chatAdapter.addMessage(ChatMessage("", false))
                binding.rvChat.scrollToPosition(streamPos)
            }
            override fun onStreamChunk(chunk: String) {
                if (streamPos >= 0) {
                    full.append(chunk)
                    chatAdapter.appendToMessage(streamPos, chunk)
                    binding.rvChat.scrollToPosition(streamPos)
                }
            }
            override fun onFinal(text: String) {
                if (streamPos >= 0) {
                    chatAdapter.updateMessage(streamPos, text)
                    binding.rvChat.scrollToPosition(streamPos)
                } else {
                    val p = chatAdapter.addMessage(ChatMessage(text, false))
                    binding.rvChat.scrollToPosition(p)
                }
            }
            override fun onError(text: String) {
                val p = chatAdapter.addMessage(ChatMessage(text, false))
                binding.rvChat.scrollToPosition(p)
            }
        }
        CommandRouter.handle(text, currentGemini(), tts, ui)
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition available nahi hai.", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@MainActivity, "Bolein...", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                Toast.makeText(this@MainActivity, "Sun nahi saka.", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(r: Bundle?) {
                val matches = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) sendMessage(matches[0])
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Mic start nahi ho saka.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- ON/OFF toggle ----------------

    private fun setupToggle() {
        binding.toggleService.setOnCheckedChangeListener { _, isChecked ->
            val apiKey = binding.etApiKey.text.toString().trim()
            if (isChecked) {
                if (apiKey.isEmpty()) {
                    Toast.makeText(this, "Pehle Gemini API Key dalein.", Toast.LENGTH_SHORT).show()
                    binding.toggleService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putString("GEMINI_API_KEY", apiKey).apply()
                persistChatModel()

                if (!BossAccessibilityService.isInstanceActive()) {
                    Toast.makeText(this, "Pehle Boss ko Accessibility mein ON karein.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    binding.toggleService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Pehle 'Display over other apps' permission dein.", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                    binding.toggleService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                startForegroundService(Intent(this, OrbService::class.java))
                binding.tvStatus.text = "Status: On (orb chal raha hai)"
            } else {
                stopService(Intent(this, OrbService::class.java))
                binding.tvStatus.text = "Status: Off"
            }
        }
    }

    // ---------------- Video tab ----------------

    private fun setupVideo() {
        binding.btnGenerate.setOnClickListener { onGenerateVideo() }
    }

    private fun onGenerateVideo() {
        val prompt = binding.etVideoPrompt.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Pehle video prompt likhein.", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = binding.etApiKey.text.toString().trim()
            .ifEmpty { prefs.getString("GEMINI_API_KEY", "").orEmpty() }
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Pehle Gemini API Key dalein.", Toast.LENGTH_SHORT).show()
            return
        }
        val veoModel = binding.etVeoModel.text.toString().trim().ifEmpty { defaultVeoModel }
        prefs.edit().putString("VEO_MODEL", veoModel).apply()

        val aspect = if (binding.rb169.isChecked) "16:9" else "9:16"
        val duration = when (binding.rgDuration.checkedRadioButtonId) {
            R.id.rb30s -> 30
            R.id.rb60s -> 60
            R.id.rb90s -> 90
            else -> 15
        }

        // API 26-28 needs storage permission for Movies/Boss.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
            return
        }

        binding.btnGenerate.isEnabled = false
        binding.progressBarVideo.visibility = View.VISIBLE
        binding.tvVideoProgress.text = "Shuru ho raha hai..."
        binding.videoView.visibility = View.GONE

        val workDir = File(cacheDir, "veo_clips")
        VideoGenerator(apiKey).generate(
            prompt, aspect, duration, veoModel, workDir,
            object : VideoGenerator.Listener {
                override fun onProgress(text: String) {
                    binding.tvVideoProgress.text = text
                }
                override fun onComplete(finalFile: File) {
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
                        binding.videoView.setMediaController(MediaController(this@MainActivity).also {
                            it.setAnchorView(binding.videoView)
                        })
                        binding.videoView.start()
                    } else {
                        binding.tvVideoProgress.text = "Video bani lekin save nahi ho saki."
                    }
                }
                override fun onError(message: String) {
                    binding.btnGenerate.isEnabled = true
                    binding.progressBarVideo.visibility = View.GONE
                    binding.tvVideoProgress.text = "Error: $message"
                }
            }
        )
    }

    /** Saves to Movies/Boss/ and returns a playable Uri (null on failure). */
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

    // ---------------- TTS ----------------

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
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
