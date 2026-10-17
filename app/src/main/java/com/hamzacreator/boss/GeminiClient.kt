package com.hamzacreator.boss

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini REST client with TRUE SSE streaming.
 *
 * Fixes vs Gemini's draft:
 *  - gemini-1.5-flash is RETIRED → primary is now gemini-3-flash-preview
 *    (proven working on Hamza's free key), fallback gemini-flash-latest.
 *  - Real server-sent-events streaming: reads the streamGenerateContent
 *    event stream line-by-line and fires onChunk as each text piece
 *    arrives (the draft buffered the whole response).
 *  - Retry: on HTTP 429/503 waits 2s, then retries ONCE with the
 *    fallback model.
 *  - Every callback is posted to the main thread via Handler, so UI
 *    and TTS calls are always safe.
 */
class GeminiClient(private val apiKey: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Chat model, selectable in the app's UI. Default is the verified
     * working gemini-3-flash-preview; the user can pick
     * gemini-flash-latest or type any custom model name (e.g. a future
     * "Gemini Omni Flash").
     */
    var chatModel: String = "gemini-3-flash-preview"
        private set

    private val fallbackModel = "gemini-flash-latest"

    /** Called from MainActivity / OrbService when the user picks a model. */
    fun setChatModel(model: String) {
        val m = model.trim()
        if (m.isNotEmpty()) chatModel = m
    }

    private val systemPrompt =
        "You are Boss, a helpful AI assistant created by Hamza Creator. " +
                "Reply in Roman Urdu unless the user writes in another language. " +
                "Keep replies concise and friendly. " +
                "If anyone asks who made you, who created you, or who your creator is, " +
                "always say Hamza Creator made you — never say Google or anyone else made you. " +
                "If asked about your specs, features, or any problem, always refer to Hamza Creator."

    private fun streamUrl(model: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"

    private fun requestBody(userText: String): String =
        JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userText)))
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.7))
            .toString()

    /**
     * Streams a conversational reply. onChunk fires per text piece as it
     * arrives; onDone fires once with the full text; onError on failure.
     * All on the main thread.
     */
    fun chatStream(
        userText: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            val full = StringBuilder()
            // Attempt 1: the user's selected chat model.
            val primary = chatModel
            val fallback = if (primary == fallbackModel) "gemini-3-flash-preview" else fallbackModel
            val ok = runStream(primary, userText, full,
                onChunk = { post { onChunk(it) } })
            if (ok) {
                val text = full.toString().trim()
                if (text.isEmpty()) post { onError("Jawab khaali aaya.") }
                else post { onDone(text) }
                return@Thread
            }
            // Attempt 2 (only after 429/503): wait 2s, fallback model.
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
            }
            val full2 = StringBuilder()
            val ok2 = runStream(fallback, userText, full2,
                onChunk = { post { onChunk(it) } })
            if (ok2) {
                val text = full2.toString().trim()
                if (text.isEmpty()) post { onError("Jawab khaali aaya.") }
                else post { onDone(text) }
            } else {
                post { onError("Gemini busy hai, thodi der baad try karein.") }
            }
        }.start()
    }

    /**
     * Runs one SSE stream. Returns true on clean completion, false when
     * the caller should retry (HTTP 429/503) or give up.
     */
    private fun runStream(
        model: String,
        userText: String,
        sink: StringBuilder,
        onChunk: (String) -> Unit
    ): Boolean {
        var response: okhttp3.Response? = null
        try {
            val body = requestBody(userText).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(streamUrl(model)).post(body).build()
            response = client.newCall(request).execute()

            if (response.code == 429 || response.code == 503) {
                return false // signal: retry with fallback
            }
            if (!response.isSuccessful) {
                return true // non-retryable; don't loop models forever
            }

            val source = response.body?.source() ?: return true
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val chunkText = extractText(data)
                if (chunkText.isNotEmpty()) {
                    sink.append(chunkText)
                    onChunk(chunkText)
                }
            }
            return true
        } catch (_: Exception) {
            return true // network error: don't retry-loop here
        } finally {
            try {
                response?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Pulls all "text" fields out of one SSE JSON chunk. */
    private fun extractText(json: String): String {
        return try {
            val sb = StringBuilder()
            val candidates = JSONObject(json).optJSONArray("candidates") ?: return ""
            for (i in 0 until candidates.length()) {
                val parts = candidates.getJSONObject(i)
                    .optJSONObject("content")
                    ?.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(j).optString("text", ""))
                }
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
