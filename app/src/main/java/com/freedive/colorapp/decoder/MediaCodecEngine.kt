// ============================================================================
//  MEDIACODEC ENGINE · giai ma cung HEVC 10-bit (D-Log M)
//  MediaExtractor -> MediaCodec (async) -> ImageReader (PRIVATE, GPU_SAMPLED)
//  -> Image.hardwareBuffer -> NativeBridge.submitFrame (zero-copy sang Vulkan)
//
//  BO SUNG — TRANSPORT (xem/tua khi dang chinh mau):
//    · Phat dung toc do thuc (truoc day giai ma het suc, clip vut qua vai giay)
//    · play / pause / toggle — dung o dung frame de grade
//    · seekTo(us): SEEK_TO_PREVIOUS_SYNC + flush, giai ma bo qua den dung frame
//    · stepFrame(±1): buoc tung frame
//    · Lap lai (loop) khi het clip neu dang phat
//    · onPosition: bao vi tri cho thanh transport
//  Input buffer duoc XEP HANG (availableInputs) thay vi nap ngay, de khi tam
//  dung thi decoder khong chay tiep.
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
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.freedive.colorapp.NativeBridge

class MediaCodecEngine(private val context: Context) {

    companion object {
        private const val TAG = "FDC/Decoder"
        private const val MAX_IMAGES = 4
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private val thread = HandlerThread("fdc-decode").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    var isMain10 = false
        private set
    var durationUs: Long = 0
        private set
    /** Do dai 1 frame (us) — dung cho buoc tung frame */
    var frameDurationUs: Long = 33_333
        private set

    /** Goi moi frame voi presentationTimeUs (tren decode thread) — keyframing tick */
    var onFrameTime: ((Long) -> Unit)? = null
    /** Bao vi tri phat cho UI (goi tren MAIN thread, da tiet lieu) */
    var onPosition: ((Long) -> Unit)? = null
    /** Bao trang thai phat/dung cho UI (MAIN thread) */
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    @Volatile var isPlaying = false
        private set
    @Volatile var positionUs = 0L
        private set

    private val lock = Object()
    private val availableInputs = ArrayDeque<Int>()
    @Volatile private var seekTargetUs = -1L      // >=0 : dang tua den moc nay
    @Volatile private var inputDone = false
    @Volatile private var released = false

    // Neo dong bo thoi gian thuc
    private var anchorPtsUs = -1L
    private var anchorWallMs = 0L
    private var lastReportMs = 0L

    // ------------------------------------------------------------------------
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
        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
            runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30) else 30
        frameDurationUs = if (fps > 0) 1_000_000L / fps else 33_333L

        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        Log.i(TAG, "HEVC ${w}x$h main10=$isMain10 fps=$fps dur=${durationUs}us")

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

    /** Bat dau giai ma + phat */
    fun start() {
        codec?.start() ?: return
        setPlaying(true)
        resetAnchor()
        handler.post { pump() }
    }

    // ---------------------------------------------------------------- TRANSPORT
    fun play() {
        if (released || isPlaying) return
        setPlaying(true)
        resetAnchor()
        handler.post { pump() }
    }

    fun pause() {
        if (released || !isPlaying) return
        setPlaying(false)
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    /**
     * Tua den [us]. Giai ma tu keyframe truoc do roi BO QUA cac frame chua toi
     * moc — frame dau tien >= moc moi duoc ve len man hinh.
     */
    fun seekTo(us: Long) {
        if (released) return
        val target = us.coerceIn(0L, if (durationUs > 0) durationUs else Long.MAX_VALUE)
        handler.post {
            val c = codec ?: return@post
            val ex = extractor ?: return@post
            seekTargetUs = target
            inputDone = false
            synchronized(lock) { availableInputs.clear() }
            runCatching {
                c.flush()
                ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                c.start()          // async mode: bat buoc start() lai sau flush()
            }.onFailure { Log.e(TAG, "Loi seek: ${it.message}") }
            positionUs = target
            resetAnchor()
            pump()
        }
    }

    /** Buoc tung frame: delta = +1 / -1 */
    fun stepFrame(delta: Int) {
        pause()
        seekTo(positionUs + delta * frameDurationUs)
    }

    private fun setPlaying(v: Boolean) {
        isPlaying = v
        main.post { onPlayStateChanged?.invoke(v) }
    }

    private fun resetAnchor() { anchorPtsUs = -1L; anchorWallMs = 0L }

    // ------------------------------------------------------------------------
    /** Chi nap input khi dang phat hoac dang tua — tam dung thi decoder dung han */
    private fun pump() {
        val c = codec ?: return
        val ex = extractor ?: return
        while (!released) {
            if (!isPlaying && seekTargetUs < 0) return
            if (inputDone) return
            val idx = synchronized(lock) {
                if (availableInputs.isEmpty()) null else availableInputs.removeFirst()
            } ?: return
            val buf = runCatching { c.getInputBuffer(idx) }.getOrNull() ?: return
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                inputDone = true
                runCatching {
                    c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } else {
                runCatching { c.queueInputBuffer(idx, 0, size, ex.sampleTime, 0) }
                ex.advance()
            }
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            synchronized(lock) { availableInputs.addLast(index) }
            pump()
        }

        override fun onOutputBufferAvailable(
            mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo
        ) {
            if (released) { runCatching { mc.releaseOutputBuffer(index, false) }; return }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                runCatching { mc.releaseOutputBuffer(index, false) }
                onEndOfStream()
                return
            }

            val pts = info.presentationTimeUs
            val target = seekTargetUs

            // --- Dang tua: bo qua moi frame chua toi moc ---
            if (target >= 0) {
                if (pts + frameDurationUs / 2 < target) {
                    runCatching { mc.releaseOutputBuffer(index, false) }   // khong ve
                    return
                }
                seekTargetUs = -1L
                positionUs = pts
                onFrameTime?.invoke(pts)
                runCatching { mc.releaseOutputBuffer(index, true) }
                reportPosition(force = true)
                resetAnchor()
                return
            }

            // --- Dang tam dung: ve frame nay roi thoi ---
            if (!isPlaying) {
                positionUs = pts
                onFrameTime?.invoke(pts)
                runCatching { mc.releaseOutputBuffer(index, true) }
                reportPosition(force = true)
                return
            }

            // --- Dang phat: dong bo theo thoi gian thuc ---
            val now = SystemClock.uptimeMillis()
            if (anchorPtsUs < 0) { anchorPtsUs = pts; anchorWallMs = now }
            val dueMs = anchorWallMs + (pts - anchorPtsUs) / 1000L
            val wait = dueMs - now
            if (wait in 1..400) runCatching { Thread.sleep(wait) }

            positionUs = pts
            onFrameTime?.invoke(pts)
            runCatching { mc.releaseOutputBuffer(index, true) }
            reportPosition(force = false)
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Loi decoder: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format: $format")
        }
    }

    /** Het clip: lap lai tu dau neu dang phat (tien cho viec soi mau) */
    private fun onEndOfStream() {
        if (!isPlaying) { positionUs = durationUs; reportPosition(true); return }
        val c = codec ?: return
        val ex = extractor ?: return
        inputDone = false
        synchronized(lock) { availableInputs.clear() }
        runCatching {
            c.flush()
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            c.start()
        }
        positionUs = 0
        resetAnchor()
        pump()
    }

    private fun reportPosition(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastReportMs < 100) return
        lastReportMs = now
        val p = positionUs
        main.post { onPosition?.invoke(p) }
    }

    /** Lay frame moi nhat, chuyen HardwareBuffer sang Vulkan (zero-copy) */
    private fun drainImage(reader: ImageReader) {
        val image: Image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val hb = image.hardwareBuffer
            if (hb != null) {
                if (!NativeBridge.submitFrame(hb)) {
                    Log.e(TAG, "submitFrame that bai: ${NativeBridge.lastError()}")
                }
                hb.close()
            }
        } finally {
            image.close()
        }
    }

    fun release() {
        released = true
        isPlaying = false
        runCatching { codec?.stop() }
        codec?.release(); codec = null
        extractor?.release(); extractor = null
        imageReader?.close(); imageReader = null
        thread.quitSafely()
    }
}
