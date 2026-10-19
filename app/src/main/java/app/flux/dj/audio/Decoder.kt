package app.flux.dj.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes any Android-supported audio file to 44.1 kHz interleaved stereo PCM16. */
object Decoder {
    private const val MAX_SECONDS = 12 * 60

    /** [window] = decode only ~80 s from the middle of the file (for fast library indexing). */
    fun decode(
        ctx: Context, uri: Uri, id: String, title: String, artist: String, window: Boolean = false,
        onProgress: (Float) -> Unit = {},
        /** Streaming: called once ~1 s is decoded with a Track that keeps filling up in the background. */
        onStream: ((Track) -> Unit)? = null,
    ): Track? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var ti = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) return null
            ex.selectTrack(ti)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (window && durUs > 120_000_000L) ex.seekTo(durUs / 2 - 40_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var out = ShortArray(maxOf(SR * 2 * 30, ((durUs / 1_000_000.0 + 2) * SR * 2).toInt().coerceAtMost(SR * 2 * (if (window) 80 else MAX_SECONDS))))
            var outLen = 0
            var stream: Track? = null
            // resampler state
            var srcPos = 0.0
            var pl = 0f; var pr = 0f // previous source frame
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            val limit = SR * 2 * (if (window) 80 else MAX_SECONDS)
            var chunk = ShortArray(8192)
            fun ensure(n: Int): Boolean {
                if (outLen + n <= out.size) return true
                if (out.size >= limit) { outDone = true; return false }
                out = out.copyOf(minOf(limit, maxOf(out.size * 3 / 2, outLen + n)))
                stream?.let { st -> st.pcm = out; st.frames = out.size / 2 }
                return true
            }
            while (!outDone) {
                var progressed = false
                // feed every free input buffer without waiting
                while (!inDone) {
                    val ii = codec.dequeueInputBuffer(0)
                    if (ii < 0) break
                    progressed = true
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                        ex.advance()
                    }
                }
                // drain every ready output buffer; only block briefly when nothing moved
                while (!outDone) {
                    val oi = codec.dequeueOutputBuffer(info, if (progressed) 0 else 2000)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = codec.outputFormat
                        inRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inCh = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        continue
                    }
                    if (oi < 0) continue
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.order(ByteOrder.LITTLE_ENDIAN)
                    val total = info.size / 2
                    if (chunk.size < total) chunk = ShortArray(total)
                    ob.asShortBuffer().get(chunk, 0, total)
                    val frames = total / inCh
                    if (inRate == SR) {
                        // fast path: no resampling
                        if (ensure(frames * 2)) {
                            if (inCh == 2) { System.arraycopy(chunk, 0, out, outLen, frames * 2); outLen += frames * 2 }
                            else for (f in 0 until frames) { val a = f * inCh; out[outLen++] = chunk[a]; out[outLen++] = if (inCh > 1) chunk[a + 1] else chunk[a] }
                        }
                    } else {
                        // linear resample; frame -1 is the last frame of the previous chunk
                        val step = inRate.toDouble() / SR
                        ensure(((frames + 2) / step).toInt() * 2 + 4)
                        while (!outDone) {
                            val i0 = Math.floor(srcPos).toInt()
                            if (i0 + 1 >= frames) break
                            val fr = (srcPos - i0).toFloat()
                            val l0: Float; val r0: Float
                            if (i0 < 0) { l0 = pl; r0 = pr } else {
                                val a = i0 * inCh
                                l0 = chunk[a].toFloat(); r0 = if (inCh > 1) chunk[a + 1].toFloat() else l0
                            }
                            val bb = (i0 + 1) * inCh
                            val l1 = chunk[bb].toFloat(); val r1 = if (inCh > 1) chunk[bb + 1].toFloat() else l1
                            if (!ensure(2)) break
                            out[outLen++] = (l0 + (l1 - l0) * fr).toInt().toShort()
                            out[outLen++] = (r0 + (r1 - r0) * fr).toInt().toShort()
                            srcPos += step
                        }
                        if (frames > 0) srcPos -= frames
                    }
                    if (frames > 0) {
                        val a = (frames - 1) * inCh
                        pl = chunk[a].toFloat(); pr = if (inCh > 1) chunk[a + 1].toFloat() else pl
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (onStream != null && !window) {
                        val st = stream
                        if (st == null && outLen >= SR * 2) {
                            stream = Track(id, title, artist, out).also { s -> s.complete = false; s.available = outLen / 2; onStream(s) }
                        } else if (st != null) st.available = outLen / 2
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (outLen < SR) return null
            val st = stream
            if (st != null) {
                // keep the same array (the deck may be playing it); just publish the true length
                st.pcm = out; st.frames = outLen / 2; st.available = outLen / 2; st.complete = true
                return st
            }
            return Track(id, title, artist, if (outLen == out.size) out else out.copyOf(outLen))
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
