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
    private const val START_SECONDS = 30
    /** Give up when neither the extractor nor the codec made progress for this long. */
    private const val STALL_NS = 30_000_000_000L

    fun decode(
        ctx: Context, uri: Uri, id: String, title: String, artist: String,
        onProgress: (Float) -> Unit = {},
        /** Streaming: called once ~1 s is decoded with a Track that keeps filling up in the background. */
        onStream: ((Track) -> Unit)? = null,
        /** Extra HTTP headers (Plex token etc.). http(s) URIs are streamed directly. */
        headers: Map<String, String> = emptyMap(),
    ): Track? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var temp: java.io.File? = null
        try {
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") ex.setDataSource(uri.toString(), headers)
            else try {
                ex.setDataSource(ctx, uri, null)
            } catch (e: Exception) {
                // Some cloud providers can't give MediaExtractor a seekable descriptor. Fetch once
                // into the cache and decode locally; the PCM cache then makes replays instant.
                if (scheme != "content") throw e
                val f = java.io.File.createTempFile("flux-remote", ".audio", ctx.cacheDir)
                temp = f
                ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                ex.setDataSource(f.absolutePath)
            }
            var ti = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) return null
            ex.selectTrack(ti)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (inCh <= 0 || inRate <= 0) return null
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val limit = SR * 2 * MAX_SECONDS
            // Start small and grow: preallocating the whole file cost ~42 MB for a 4-minute track.
            var out = ShortArray(minOf(limit, SR * 2 * START_SECONDS))
            var outLen = 0
            var stream: Track? = null
            // resampler state
            var srcPos = 0.0
            var pl = 0f; var pr = 0f // previous source frame
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var chunk = ShortArray(8192)
            var hitLimit = false
            // Causal 4-tap low-pass for high-rate sources (88.2/96/192 kHz) before decimation.
            var aaL1 = 0f; var aaL2 = 0f; var aaL3 = 0f
            var aaR1 = 0f; var aaR2 = 0f; var aaR3 = 0f
            fun ensure(n: Int): Boolean {
                if (outLen + n <= out.size) return true
                if (outLen + n > limit || out.size >= limit) { outDone = true; hitLimit = true; return false }
                out = out.copyOf(minOf(limit, maxOf(out.size * 3 / 2, outLen + n)))
                stream?.let { st -> st.pcm = out; st.frames = outLen / 2; st.available = outLen / 2 }
                return true
            }
            // Only MP3 frames are small enough to pack safely; other containers keep one sample
            // per buffer (a FLAC frame or PCM chunk may be larger than one codec buffer).
            val packFrames = fmt.getString(MediaFormat.KEY_MIME)?.lowercase() == "audio/mpeg"
            var lastProgress = System.nanoTime()
            var eosQueued = false
            var lastProgressCb = 0L
            while (!outDone) {
                if (System.nanoTime() - lastProgress > STALL_NS) {
                    android.util.Log.w("FLUX", "decode stalled for $uri")
                    return null
                }
                var progressed = false
                // Feed every free input buffer, packing as many source frames into it as fit.
                // One codec wake-up per MP3 frame capped the whole decode at ~10x real time
                // (a four-minute track took 20 s even though it was already on disk).
                while (!eosQueued) {
                    val ii = codec.dequeueInputBuffer(if (progressed) 0 else 10_000)
                    if (ii < 0) break
                    progressed = true
                    val buf = codec.getInputBuffer(ii)!!
                    buf.clear()
                    var off = 0
                    var stamp = -1L
                    if (!inDone) {
                        if (packFrames) {
                            while (buf.capacity() - off >= 2048) {
                                val n = ex.readSampleData(buf, off)
                                if (n < 0) { inDone = true; break }
                                if (stamp < 0) stamp = ex.sampleTime
                                off += n
                                if (!ex.advance()) { inDone = true; break }
                            }
                        } else {
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) inDone = true
                            else {
                                stamp = ex.sampleTime; off = n
                                if (!ex.advance()) inDone = true
                            }
                        }
                    }
                    if (off > 0) {
                        codec.queueInputBuffer(ii, 0, off, stamp.coerceAtLeast(0L), 0)
                        val now = System.nanoTime()
                        if (durUs > 0 && stamp >= 0 && now - lastProgressCb > 200_000_000L) {
                            lastProgressCb = now
                            onProgress((stamp.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                        lastProgress = now
                    } else {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
                // Drain every ready output buffer; block only when nothing moved so the codec
                // gets a chance to deliver a whole batch at once.
                while (!outDone) {
                    val oi = codec.dequeueOutputBuffer(info, if (progressed) 0 else 10_000)
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
                    ob.position(info.offset)
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
                        if (inRate > SR * 3 / 2) {
                            for (f in 0 until frames) {
                                val a = f * inCh
                                val l = chunk[a].toFloat()
                                val r = if (inCh > 1) chunk[a + 1].toFloat() else l
                                val yl = (l + 2 * aaL1 + 2 * aaL2 + aaL3) / 6f
                                val yr = (r + 2 * aaR1 + 2 * aaR2 + aaR3) / 6f
                                aaL3 = aaL2; aaL2 = aaL1; aaL1 = l
                                aaR3 = aaR2; aaR2 = aaR1; aaR1 = r
                                chunk[a] = (yl + 0.5f).toInt().toShort()
                                if (inCh > 1) chunk[a + 1] = (yr + 0.5f).toInt().toShort()
                            }
                        }
                        // Linear resample; frame -1 is the last frame of the previous chunk.
                        // One ensure() for the whole chunk and local array/offset variables: the
                        // per-sample closure call was ~460 ns per output frame on the tablet.
                        val step = inRate.toDouble() / SR
                        if (ensure((((frames + 2) / step).toInt() + 4) * 2)) {
                            var o = out
                            var w = outLen
                            val cap = o.size
                            while (true) {
                                val i0 = if (srcPos < 0.0) -1 else srcPos.toInt()
                                if (i0 + 1 >= frames) break
                                if (w + 2 > cap) {
                                    outLen = w
                                    if (!ensure(2)) { o = out; w = outLen; break }
                                    o = out; w = outLen
                                }
                                val fr = (srcPos - i0).toFloat()
                                val l0: Float; val r0: Float
                                if (i0 < 0) { l0 = pl; r0 = pr } else {
                                    val a = i0 * inCh
                                    l0 = chunk[a].toFloat(); r0 = if (inCh > 1) chunk[a + 1].toFloat() else l0
                                }
                                val bb = (i0 + 1) * inCh
                                val l1 = chunk[bb].toFloat(); val r1 = if (inCh > 1) chunk[bb + 1].toFloat() else l1
                                o[w++] = (l0 + (l1 - l0) * fr).toInt().toShort()
                                o[w++] = (r0 + (r1 - r0) * fr).toInt().toShort()
                                srcPos += step
                            }
                            outLen = w
                        }
                        if (frames > 0) srcPos -= frames
                    }
                    if (frames > 0) {
                        val a = (frames - 1) * inCh
                        pl = chunk[a].toFloat(); pr = if (inCh > 1) chunk[a + 1].toFloat() else pl
                    }
                    codec.releaseOutputBuffer(oi, false)
                    lastProgress = System.nanoTime()
                    if (onStream != null) {
                        val st = stream
                        if (st == null && outLen >= SR * 2) {
                            stream = Track(id, title, artist, out).also { s ->
                                s.complete = false
                                s.frames = outLen / 2
                                s.available = outLen / 2
                                onStream(s)
                            }
                        } else if (st != null) { st.frames = outLen / 2; st.available = outLen / 2 }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (outLen < SR) return null
            val st = stream
            if (st != null) {
                // keep the same array (the deck may be playing it); just publish the true length
                st.pcm = out; st.frames = outLen / 2; st.available = outLen / 2
                st.complete = true; st.truncated = hitLimit
                return st
            }
            return Track(id, title, artist, if (outLen == out.size) out else out.copyOf(outLen)).also { it.truncated = hitLimit }
        } catch (e: Exception) {
            android.util.Log.w("FLUX", "decode failed for $uri: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
            try { temp?.delete() } catch (_: Exception) {}
        }
    }
}
