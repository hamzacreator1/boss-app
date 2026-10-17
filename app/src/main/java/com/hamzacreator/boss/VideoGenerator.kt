package com.hamzacreator.boss

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Long-video pipeline — NO 10-second limit.
 *
 * Veo generates ~8s clips, so for longer durations this auto-chains:
 * generates N sequential 8-second clips (each prompted as
 * "part K of N, continuous scene" for visual continuity), then stitches
 * them on-device into one MP4 with VideoStitcher (no re-encode).
 *
 *   15s -> 2 clips, 30s -> 4 clips, 60s -> 8 clips, 90s -> 12 clips.
 */
class VideoGenerator(private val apiKey: String) {

    interface Listener {
        fun onProgress(text: String)
        fun onComplete(finalFile: File)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // Gemini API uses the -preview model IDs; the Vertex -001 IDs ALWAYS 404 here.
    private val veoFallbackModel = "veo-3.1-fast-generate-preview"

    fun generate(
        prompt: String,
        aspectRatio: String,
        durationSec: Int,
        veoModel: String,
        workDir: File,
        listener: Listener
    ) {
        Thread {
            try {
                if (!looksLikeVeoModel(veoModel)) {
                    throw Exception(
                        "❌ Ye model naam nahi mila. Sahi naam hai: veo-3.1-generate-preview"
                    )
                }
                if (!workDir.exists()) workDir.mkdirs()
                val total = (durationSec + 7) / 8  // ceil
                val veo = VeoClient(apiKey)
                val models = listOf(veoModel, veoFallbackModel).distinct()
                val clips = mutableListOf<File>()

                for (k in 1..total) {
                    post { listener.onProgress("Clip $k/$total ban rahi hai...") }
                    val clipPrompt = "$prompt (part $k of $total, continuous scene, same style and characters)"
                    var clip: File? = null
                    var lastErr: Exception? = null
                    for (m in models) {
                        try {
                            clip = veo.generateClip(clipPrompt, aspectRatio, m, workDir)
                            break
                        } catch (e: Exception) {
                            lastErr = e
                        }
                    }
                    if (clip == null) {
                        throw lastErr ?: Exception("Clip $k nahi ban saki.")
                    }
                    clips.add(clip)
                    val done = k
                    post { listener.onProgress("Clip $done/$total taiyar ✓") }
                }

                post { listener.onProgress("Clips jor raha hoon...") }
                val out = File(workDir, "boss_video_${System.currentTimeMillis()}.mp4")
                if (!VideoStitcher.stitch(clips, out)) {
                    throw Exception("Clips jorne mein masla hua.")
                }
                clips.forEach { c -> try { c.delete() } catch (_: Exception) {} }
                post { listener.onComplete(out) }
            } catch (e: Exception) {
                post { listener.onError(friendlyVeoError(e.message ?: "Video nahi ban saki.")) }
            }
        }.start()
    }

    /** Turns raw Veo HTTP errors into clear Roman Urdu guidance. */
    private fun friendlyVeoError(raw: String): String {
        return when {
            raw.contains("404") ->
                "❌ Ye model naam nahi mila. Sahi naam hai: veo-3.1-generate-preview"
            raw.contains("403") || raw.contains("PERMISSION_DENIED", ignoreCase = true) ||
                raw.contains("billing", ignoreCase = true) || raw.contains("FAILED_PRECONDITION") ->
                "⚠️ Veo video ke liye PAID Gemini plan chahiye — free key se video NAHI banegi."
            raw.contains("400") && raw.contains("API key", ignoreCase = true) ->
                "API key ghalat hai — Settings mein sahi key dalein."
            else -> raw
        }
    }

    /** "Gemini Omni" is not a real Google model — fail fast with a clear message. */
    private fun looksLikeVeoModel(model: String): Boolean =
        model.trim().lowercase().startsWith("veo-")

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
