// ============================================================================
//  TASK 4.1 + 4.3 + 4.4 + E1/E2 — EXPORT ENGINE · decode -> grade -> encode
//  Duong du lieu (QĐ10):
//    MediaExtractor -> MediaCodec DECODER -> ImageReader(PRIVATE)
//    -> AHardwareBuffer -> renderer (export swapchain = ENCODER INPUT SURFACE)
//    -> MediaCodec ENCODER (HEVC Main10 hoac H.264/AVC 8-bit) -> MediaMuxer .mp4
//
//  E1 — CODEC & THAM SO (spec 4.1): ExportConfig chon codec/фps/bitrate/kich thuoc.
//    · HEVC_MAIN10: 10-bit, metadata BT.709 day du (nhu V1.0).
//    · AVC_8BIT: H.264 High profile — tuong thich toi da voi thiet bi khach.
//      10-bit -> 8-bit AN TOAN: pipeline noi bo fp16 xuat Rec.709; encoder
//      surface 8-bit thuc hien dithering/quantize o composite output; metadata
//      BT.709 SDR giu nguyen nen mau khong lech (chi giam do sau bit).
//  E2 — SLOW-MO 50% (spec 4.3.1): nguon 60fps giu NGUYEN SO FRAME, timestamp
//    nhan ptsScale (x2) khi ghi muxer -> clip dai gap doi, muot khong rot frame.
//  E2 — AUDIO (spec 4.3.2): mac dinh DROP track audio (bo tieng bot lan);
//    muteAudio=false -> passthrough copy nguyen ven packet audio goc (khong
//    decode/re-encode). Slow-Mo luon ep mute (audio khong khop timeline x2).
// ============================================================================
package com.freedive.colorapp.export

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.freedive.colorapp.NativeBridge
import java.io.File
import java.nio.ByteBuffer

class ExportEngine(private val context: Context) {

    companion object {
        private const val TAG = "FDC/Export"
        private const val TIMEOUT_US = 10_000L
    }

    /** callback tien trinh 0..100 (goi tren thread export) */
    var onProgress: ((Int) -> Unit)? = null

    /**
     * Xuat 1 clip DONG BO (goi tu thread nen — BatchExporter lo viec do).
     * Grade hien hanh cua renderer (da applyTo truoc khi goi) duoc ap len tung frame.
     */
    fun exportClip(uri: Uri, outFile: File, config: ExportConfig = ExportConfig()): File? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        var inFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null           // E2 — passthrough (neu co & khong mute)
        var audioTrackSrc = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (track < 0 && mime == MediaFormat.MIMETYPE_VIDEO_HEVC) { track = i; inFormat = f }
            if (audioTrackSrc < 0 && mime.startsWith("audio/")) { audioTrackSrc = i; audioFormat = f }
        }
        if (track < 0 || inFormat == null) { Log.e(TAG, "Khong co track HEVC"); return null }
        extractor.selectTrack(track)
        val w = inFormat.getInteger(MediaFormat.KEY_WIDTH)
        val h = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val srcFps = if (inFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
            inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        // E2 — Slow-Mo ep mute; passthrough chi khi toc do thuong
        val includeAudio = !config.muteAudio && config.speed == ExportSpeed.NORMAL &&
                           audioFormat != null

        // ---- E1: thong so dau ra theo config ----
        val (outW, outH) = config.fitOutput(w, h)
        val outFps = config.fpsOverride ?: srcFps
        val mime = when (config.codec) {
            ExportCodec.HEVC_MAIN10 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            ExportCodec.AVC_8BIT    -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val outFormat = MediaFormat.createVideoFormat(mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                       MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            when (config.codec) {
                ExportCodec.HEVC_MAIN10 -> setInteger(
                    MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                ExportCodec.AVC_8BIT -> setInteger(
                    MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
            // Metadata mau BT.709 SDR (Task 4.4) — dung cho ca 2 codec, tranh gamma shift
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()

        // Watermark bat/tat theo config (anh logo da nap tu MainActivity)
        NativeBridge.setWatermarkEnabled(config.watermark)

        // ---- Renderer chuyen sang export mode (swapchain = encoder surface) ----
        if (!NativeBridge.beginExport(encoderSurface, outW, outH)) {
            Log.e(TAG, "beginExport loi: ${NativeBridge.lastError()}")
            encoder.release(); extractor.release(); return null
        }
        encoder.start()

        // ---- DECODER dong bo -> ImageReader -> HardwareBuffer ----
        val reader = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 4,
                                             HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        decoder.configure(inFormat, reader.surface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxVideoTrack = -1
        var muxAudioTrack = -1
        var muxStarted = false
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var framesIn = 0L

        try {
            while (!decodeDone) {
                // 1. Nap packet vao decoder
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 2. Rut frame decode -> render qua pipeline grade -> encoder surface
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    val eos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outIdx, /*render=*/!eos)
                    if (!eos) {
                        reader.acquireLatestImage()?.use { img ->
                            img.hardwareBuffer?.let { hb ->
                                if (!NativeBridge.submitExportFrame(hb)) {
                                    Log.e(TAG, "submitExportFrame: ${NativeBridge.lastError()}")
                                }
                                hb.close()
                            }
                        }
                        framesIn++
                        if (durationUs > 0) onProgress?.invoke(
                            (bufInfo.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99))
                    } else {
                        encoder.signalEndOfInputStream()
                        decodeDone = true
                    }
                }
                // 3. Rut packet encoder -> muxer (khong de encoder nghen)
                drainEncoder(encoder, muxer, bufInfo, config.speed.ptsScale,
                    audioFormat.takeIf { includeAudio },
                    onTracks = { v, a -> muxVideoTrack = v; muxAudioTrack = a; muxStarted = true },
                    videoTrack = { muxVideoTrack }, muxStarted = muxStarted, untilEos = false)
            }
            // 4. Rut not encoder den EOS
            drainEncoder(encoder, muxer, bufInfo, config.speed.ptsScale,
                audioFormat.takeIf { includeAudio },
                onTracks = { v, a -> muxVideoTrack = v; muxAudioTrack = a; muxStarted = true },
                videoTrack = { muxVideoTrack }, muxStarted = muxStarted, untilEos = true)
            // 5. E2 — passthrough audio: copy nguyen ven packet sau khi video xong
            if (includeAudio && muxAudioTrack >= 0) {
                copyAudioTrack(uri, audioTrackSrc, muxer, muxAudioTrack)
            }
            onProgress?.invoke(100)
        } finally {
            NativeBridge.endExport()                       // khoi phuc preview + tra VRAM
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
            runCatching { if (muxStarted) muxer.stop() }; muxer.release()
            reader.close(); extractor.release()
        }
        Log.i(TAG, "Xuat xong $framesIn frame -> ${outFile.name} " +
                   "(${config.codec}, ${outW}x${outH}@${outFps}, ${config.bitrateMbps}Mbps" +
                   (if (config.speed == ExportSpeed.SLOWMO_50) ", slow-mo x2" else "") +
                   (if (includeAudio) ", audio passthrough" else ", muted") + ")")
        return outFile
    }

    /**
     * Rut encoder ra muxer. ptsScale (E2): timestamp video NHAN he so nay khi ghi
     * (x2 = slow-mo 50% — nguon 60fps phat nhu 30fps, GIU nguyen so frame).
     * pendingAudioFormat != null: track audio duoc add TRUOC muxer.start() de
     * copy passthrough o buoc 5.
     */
    private inline fun drainEncoder(
        encoder: MediaCodec, muxer: MediaMuxer, info: MediaCodec.BufferInfo,
        ptsScale: Long, pendingAudioFormat: MediaFormat?,
        onTracks: (video: Int, audio: Int) -> Unit, videoTrack: () -> Int,
        muxStarted: Boolean, untilEos: Boolean
    ) {
        var started = muxStarted
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0L)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val v = muxer.addTrack(encoder.outputFormat)
                    val a = pendingAudioFormat?.let { muxer.addTrack(it) } ?: -1
                    onTracks(v, a)
                    muxer.start(); started = true
                }
                idx >= 0 -> {
                    val data: ByteBuffer = encoder.getOutputBuffer(idx)!!
                    if (info.size > 0 && started &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        info.presentationTimeUs *= ptsScale        // E2 — Slow-Mo
                        muxer.writeSampleData(videoTrack(), data, info)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> if (!untilEos) return   // untilEos: cho tiep den EOS
            }
        }
    }

    /** E2 — copy NGUYEN VEN (khong decode) packet audio goc vao muxer. */
    private fun copyAudioTrack(uri: Uri, srcTrack: Int, muxer: MediaMuxer, dstTrack: Int) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            ex.selectTrack(srcTrack)
            val maxSize = ex.getTrackFormat(srcTrack).let {
                if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            }
            val buf = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = ex.sampleTime
                info.offset = 0
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(dstTrack, buf, info)
                ex.advance()
            }
        } finally { ex.release() }
    }
}
