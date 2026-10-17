package com.hamzacreator.boss

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Google Veo video generation via the Gemini API.
 *
 * Flow per clip:
 *   1. POST /v1beta/models/{MODEL}:predictLongRunning
 *      body {"instances":[{"prompt":"..."}],"parameters":{"aspectRatio":"9:16"}}
 *      -> {"name": "models/.../operations/xxx"}
 *   2. Poll GET /v1beta/{operationName} until done=true
 *   3. Read response.generatedVideos[0].video.uri, download the bytes
 *      (append ?key= API key) into a temp MP4 file.
 *
 * NOTE: Veo is a PAID model — a free-tier API key gets an error here.
 * The UI says so plainly; this client just surfaces the error text.
 */
class VeoClient(private val apiKey: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Generates ONE ~8s clip. Blocks; call off the main thread. */
    @Throws(Exception::class)
    fun generateClip(prompt: String, aspectRatio: String, model: String, workDir: File): File {
        val operationName = startGeneration(prompt, aspectRatio, model)
        val videoUri = pollUntilDone(operationName)
        return downloadVideo(videoUri, workDir)
    }

    private fun startGeneration(prompt: String, aspectRatio: String, model: String): String {
        val body = JSONObject()
            .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            .put("parameters", JSONObject().put("aspectRatio", aspectRatio))
            .toString()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:predictLongRunning?key=$apiKey")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw Exception("Veo start failed (HTTP ${resp.code}): ${text.take(220)}")
            }
            val name = JSONObject(text).optString("name")
            if (name.isEmpty()) throw Exception("Veo ne operation name nahi diya.")
            return name
        }
    }

    private fun pollUntilDone(operationName: String): String {
        val deadline = System.currentTimeMillis() + 12 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {
            }
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$operationName?key=$apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw Exception("Veo poll failed (HTTP ${resp.code}): ${text.take(220)}")
                }
                val root = JSONObject(text)
                if (root.optBoolean("done", false)) {
                    val videos = root.optJSONObject("response")?.optJSONArray("generatedVideos")
                    val uri = if (videos != null && videos.length() > 0) {
                        videos.getJSONObject(0).optJSONObject("video")?.optString("uri")
                    } else null
                    if (uri.isNullOrEmpty()) throw Exception("Veo done=true lekin video URI nahi mili.")
                    return uri
                }
                val err = root.optJSONObject("error")?.optString("message")
                if (!err.isNullOrEmpty()) throw Exception("Veo error: $err")
            }
        }
        throw Exception("Video taiyar nahi hui (12 min timeout).")
    }

    private fun downloadVideo(videoUri: String, workDir: File): File {
        val url = if (videoUri.contains("?")) "$videoUri&key=$apiKey" else "$videoUri?key=$apiKey"
        val out = File(workDir, "clip_${System.currentTimeMillis()}.mp4")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Video download failed (HTTP ${resp.code}).")
            }
            resp.body!!.byteStream().use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        }
        if (!out.exists() || out.length() == 0L) throw Exception("Downloaded clip khaali hai.")
        return out
    }
}
