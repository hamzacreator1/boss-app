package com.hamzacreator.boss

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Stitches multiple MP4 clips into ONE MP4 without re-encoding.
 *
 * All Veo clips share the same encoding, so samples are copied
 * directly: tracks are registered from the first clip, then every
 * clip's samples are written with per-track presentation-time offsets.
 */
object VideoStitcher {

    /** Returns true on success. */
    fun stitch(clips: List<File>, output: File): Boolean {
        if (clips.isEmpty()) return false
        var muxer: MediaMuxer? = null
        try {
            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Register tracks from the first clip.
            val first = MediaExtractor()
            first.setDataSource(clips[0].absolutePath)
            val trackCount = first.trackCount
            if (trackCount == 0) {
                first.release()
                return false
            }
            val muxerTrackIndex = IntArray(trackCount)
            for (i in 0 until trackCount) {
                val format: MediaFormat = first.getTrackFormat(i)
                muxerTrackIndex[i] = muxer.addTrack(format)
            }
            first.release()
            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val trackOffsets = LongArray(trackCount) { 0L }

            for (clip in clips) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(clip.absolutePath)
                    val n = minOf(trackCount, ex.trackCount)
                    for (t in 0 until n) ex.selectTrack(t)

                    val trackMaxPts = LongArray(trackCount) { 0L }
                    while (true) {
                        val track = ex.sampleTrackIndex
                        if (track < 0 || track >= trackCount) break
                        info.offset = 0
                        info.size = ex.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        val pts = ex.sampleTime
                        info.presentationTimeUs = pts + trackOffsets[track]
                        info.flags = ex.sampleFlags
                        muxer.writeSampleData(muxerTrackIndex[track], buffer, info)
                        if (pts > trackMaxPts[track]) trackMaxPts[track] = pts
                        ex.advance()
                    }
                    // Next clip starts after this clip's last frame (+~1 frame gap).
                    for (t in 0 until trackCount) {
                        trackOffsets[t] += trackMaxPts[t] + 40_000L
                    }
                } finally {
                    try {
                        ex.release()
                    } catch (_: Exception) {
                    }
                }
            }

            muxer.stop()
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }
}
