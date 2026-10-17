package com.hamzacreator.boss

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech

/**
 * Single decision point for every user query (typed in chat or spoken
 * to the orb):
 *   1. Run the local CommandParser on the ORIGINAL query.
 *   2. Phone command + service active  -> execute via accessibility,
 *      confirm briefly (chat + TTS).
 *   3. Phone command + service missing -> ask to enable accessibility.
 *   4. Anything else                   -> conversational Gemini reply
 *      (streamed to chat, then spoken via TTS).
 */
object CommandRouter {

    interface UiCallbacks {
        /** A new assistant message bubble is starting (streaming). */
        fun onStreamStart()
        /** Next streamed text chunk. */
        fun onStreamChunk(chunk: String)
        /** Final text (phone-command confirmations and finished replies). */
        fun onFinal(text: String)
        /** Something failed. */
        fun onError(text: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun handle(
        query: String,
        gemini: GeminiClient,
        tts: TextToSpeech?,
        ui: UiCallbacks
    ) {
        val action = CommandParser.parse(query)

        if (action.type != ActionType.NONE) {
            val svc = BossAccessibilityService.getInstance()
            if (svc == null) {
                val msg = "Phone control ke liye pehle Boss ko accessibility mein on karein."
                mainHandler.post {
                    ui.onFinal(msg)
                    speak(tts, msg)
                }
                return
            }
            Thread {
                val ok = action.executeOn(svc)
                val reply = if (ok) action.spokenReply
                else "Maaf karo, ye action nahi ho saka."
                mainHandler.post {
                    ui.onFinal(reply)
                    speak(tts, reply)
                }
            }.start()
            return
        }

        // Conversational question → Gemini streams the answer.
        mainHandler.post { ui.onStreamStart() }
        gemini.chatStream(
            userText = query,
            onChunk = { chunk -> ui.onStreamChunk(chunk) },
            onDone = { full ->
                ui.onFinal(full)
                speak(tts, full)
            },
            onError = { err -> ui.onError(err) }
        )
    }

    private fun speak(tts: TextToSpeech?, text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boss_utterance")
        } catch (_: Exception) {
        }
    }
}
