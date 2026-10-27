package app.flux.dj.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MicReading(val delayMs: Float, val confidence: Float)

/**
 * Measures the real, acoustic delay from "the engine writes a click" to "the microphone hears it".
 *
 * The engine's alignment loop has one bright 1568 Hz "ding" on beat 1 and three 880 Hz "tocks", so a
 * band-pass around the ding is enough to find it even in a noisy room. The delay is the time between
 * the loop's last start and the loudest ding in the recording, folded into ±2 s (any output delay
 * shorter than that is unambiguous). This is the same for the speaker and the headphones — measure
 * each one with the tablet's mic right next to it.
 */
object MicAlign {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun measure(ctx: Context, engine: Engine, timeoutMs: Long = 6500, onProgress: (Float) -> Unit = {}): MicReading? {
        if (!hasPermission(ctx)) return null
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val bufFrames = maxOf(minBuf / 2, SR / 5)
        val source = if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        var rec: AudioRecord? = try {
            AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufFrames * 2)
        } catch (_: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Exception) {}
            // Some tablets don't expose UNPROCESSED; fall back to the normal mic.
            rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufFrames * 2)
            } catch (_: Exception) { null }
        }
        val r = rec ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        // Always use the tablet's own microphone: a connected Bluetooth headset has its own mic,
        // which is not the one the user is holding next to the speaker.
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { r.setPreferredDevice(it) }
        } catch (_: Exception) {}

        // Band-pass at the ding (Q=8 rejects the 880 Hz tocks by ~10 dB) and follow its envelope.
        val bp = Biquad().apply { peak(1568.0, 8.0, 12.0) }
        val chunk = ShortArray(1024)
        val l = FloatArray(chunk.size); val rr = FloatArray(chunk.size)
        val ts = AudioTimestamp()
        var frames = 0L
        var mapFrame = -1L
        var mapNs = 0L
        var sumAbs = 0.0
        var maxAmp = 0f
        var maxFrame = -1L
        var readNs = 0L
        val t0 = System.nanoTime()
        var nextProgress = 0L
        try {
            r.startRecording()
            while (true) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                readNs = System.nanoTime()
                for (i in 0 until n) { val v = chunk[i] / 32768f; l[i] = v; rr[i] = v }
                bp.process(l, rr, n)
                // Skip the first 150 ms (filter start-up), then look for the loudest ding.
                for (i in 0 until n) {
                    val a = abs(l[i])
                    val at = frames + i
                    if (at > SR * 15 / 100) {
                        sumAbs += a
                        if (a > maxAmp) { maxAmp = a; maxFrame = at }
                    }
                }
                frames += n
                try {
                    if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && ts.framePosition >= 0) {
                        mapFrame = ts.framePosition
                        mapNs = ts.nanoTime
                    }
                } catch (_: Exception) {}
                val elapsed = (System.nanoTime() - t0) / 1_000_000L
                if (elapsed >= nextProgress) { nextProgress = elapsed + 250; onProgress((elapsed.toFloat() / timeoutMs).coerceIn(0f, 1f)) }
                if (elapsed >= timeoutMs) break
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        if (maxFrame < 0 || maxAmp < 0.01f) return null

        val captureNs = if (mapFrame >= 0) mapNs + ((maxFrame - mapFrame) * 1_000_000_000L / SR)
                         else readNs - ((frames - maxFrame) * 1_000_000_000L / SR)
        val period = 4L * 1_000_000_000L   // the calibration loop is four beats (four seconds)
        var delta = captureNs - engine.calibEpochNs
        delta = ((delta % period) + period) % period
        if (delta > period / 2) delta -= period
        val delayMs = (delta / 1_000_000.0).toFloat().coerceIn(0f, 900f)
        val mean = (sumAbs / maxOf(1L, frames - SR * 15 / 100)).toFloat().coerceAtLeast(0.0001f)
        return MicReading(delayMs, maxAmp / mean)
    }
}
