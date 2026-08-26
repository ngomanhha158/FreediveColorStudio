// ============================================================================
//  FABLE TASK 1.1 — MEDIACODEC ENGINE · giai ma cung HEVC 10-bit (D-Log M)
//  MediaExtractor -> MediaCodec (async) -> ImageReader (PRIVATE, GPU_SAMPLED)
//  -> Image.hardwareBuffer -> NativeBridge.submitFrame (zero-copy sang Vulkan)
// ============================================================================
package com.freedive.colorapp.decoder

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.freedive.colorapp.NativeBridge

class MediaCodecEngine(private val context: Context) {

    companion object {
        private const val TAG = "FDC/Decoder"
        private const val MAX_IMAGES = 4      // vong xoay buffer — du cho pipeline
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private val thread = HandlerThread("fdc-decode").apply { start() }
    private val handler = Handler(thread.looper)

    /** true neu track video la HEVC Main10 (D-Log M 10-bit cua DJI) */
    var isMain10 = false
        private set

    /** Thoi luong clip (us) — KeyframeController dung tinh tien trinh t */
    var durationUs: Long = 0
        private set

    /** Goi moi frame voi presentationTimeUs (tren decode thread) — keyframing tick */
    var onFrameTime: ((Long) -> Unit)? = null

    /**
     * Mo video (SAF Uri), chon track HEVC, cau hinh decode ra ImageReader.
     * Tra ve false kem log neu may khong co decoder HEVC 10-bit phan cung.
     */
    fun open(uri: Uri): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) {
            Log.e(TAG, "Khong tim thay track HEVC trong file")
            ex.release(); return false
        }
        ex.selectTrack(trackIndex)
        extractor = ex

        isMain10 = format.containsKey(MediaFormat.KEY_PROFILE) &&
            format.getInteger(MediaFormat.KEY_PROFILE) ==
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        Log.i(TAG, "HEVC ${w}x$h main10=$isMain10")

        // ImageReader PRIVATE + GPU_SAMPLED: decoder ghi thang buffer GPU doc duoc
        val reader = ImageReader.newInstance(
            w, h, android.graphics.ImageFormat.PRIVATE, MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        reader.setOnImageAvailableListener({ r -> drainImage(r) }, handler)
        imageReader = reader

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        c.setCallback(codecCallback, handler)
        c.configure(format, reader.surface, null, 0)
        codec = c
        return true
    }

    fun start() = codec?.start() ?: Unit

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            val ex = extractor ?: return
            val buf = mc.getInputBuffer(index) ?: return
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                mc.queueInputBuffer(index, 0, size, ex.sampleTime, 0)
                ex.advance()
            }
        }
        override fun onOutputBufferAvailable(
            mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo
        ) {
            onFrameTime?.invoke(info.presentationTimeUs)   // keyframing tick (Tuan 3)
            // render=true -> day frame sang ImageReader surface
            mc.releaseOutputBuffer(index, /*render=*/true)
        }
        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Loi decoder: ${e.diagnosticInfo}")
        }
        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format: $format")
        }
    }

    /** Lay frame moi nhat, chuyen HardwareBuffer sang Vulkan (zero-copy) */
    private fun drainImage(reader: ImageReader) {
        val image: Image = reader.acquireLatestImage() ?: return
        try {
            val hb = image.hardwareBuffer
            if (hb != null) {
                if (!NativeBridge.submitFrame(hb)) {
                    Log.e(TAG, "submitFrame that bai: ${NativeBridge.lastError()}")
                }
                hb.close()
            }
        } finally {
            image.close()   // tra buffer ve vong xoay — tranh nghen decoder
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        codec?.release(); codec = null
        extractor?.release(); extractor = null
        imageReader?.close(); imageReader = null
        thread.quitSafely()
    }
}
