// ============================================================================
//  TASK 3.2-3.5 — MAIN ACTIVITY · man hinh grade tuan 3
//  Bo cuc: player (+scopes) → hang preset/chon video → hang Copy/Paste/KF
//  → GALLERY clip cuon ngang → panel ColorSliders.
//  Moi clip giu GradeState rieng (GradeManager); slider dong bo 2 chieu voi
//  preset JSON; keyframing noi suy theo presentationTime tu decoder.
// ============================================================================
package com.freedive.colorapp

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ProgressBar
import com.freedive.colorapp.decoder.MediaCodecEngine
import com.freedive.colorapp.export.BatchExporter
import com.freedive.colorapp.grade.DraftStore
import com.freedive.colorapp.grade.GradeManager
import com.freedive.colorapp.grade.KeyframeController
import com.freedive.colorapp.grade.PresetLoader
import com.freedive.colorapp.guide.SmartGuideManager
import com.freedive.colorapp.ui.ClipGalleryView
import com.freedive.colorapp.ui.ColorSliders
import com.freedive.colorapp.ui.ScopesPopupView
import com.freedive.colorapp.ui.VideoPlayerView

class MainActivity : ComponentActivity() {

    private lateinit var playerView: VideoPlayerView
    private lateinit var scopesView: ScopesPopupView
    private lateinit var sliders: ColorSliders
    private val smartGuide = SmartGuideManager()          // Task S1 — Smart Guide
    private lateinit var gallery: ClipGalleryView
    private lateinit var gradeManager: GradeManager
    private lateinit var draftStore: DraftStore
    private lateinit var batchExporter: BatchExporter
    private lateinit var exportBar: ProgressBar
    private lateinit var lutRepo: com.freedive.colorapp.lut.LutRepository       // Task E4
    private lateinit var lutLibrary: com.freedive.colorapp.lut.LutLibraryView   // Task E4
    // Task E1/E2/E3 — hang tuy chon xuat
    private lateinit var optAvc: android.widget.ToggleButton
    private lateinit var optFps60: android.widget.ToggleButton
    private lateinit var optSlowMo: android.widget.ToggleButton
    private lateinit var optMute: android.widget.ToggleButton
    private lateinit var optWatermark: android.widget.ToggleButton
    private var watermarkLoaded = false
    private val keyframes = KeyframeController()
    private var engine: MediaCodecEngine? = null
    private var currentUri: Uri? = null

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            gallery.addClip(uri)
            selectClip(uri)
            draftStore.scheduleSave(gallery.allClips(), gradeManager.snapshot())
        }

    // TASK E4 (spec 4.4.1) — BATCH IMPORT .cube: chon NHIEU file cung luc qua SAF.
    // MIME cua .cube thuong la application/octet-stream (spec 4.2.1).
    private val pickLuts =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val imported = lutRepo.import(uris)
            toast(if (imported.isEmpty()) "Khong nhap duoc file .cube hop le"
                  else "Da nhap ${imported.size} LUT vao thu vien")
            lutLibrary.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gradeManager = GradeManager(this)
        draftStore = DraftStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Player + scopes overlay + nut cycle ----
        val playerArea = FrameLayout(this)
        playerView = VideoPlayerView(this)
        scopesView = ScopesPopupView(this)
        playerArea.addView(playerView, mp())
        playerArea.addView(scopesView, mp())
        // TASK 5.3 — Before/After: nhan giu player (>=200ms) = xem anh goc
        val holdHandler = Handler(Looper.getMainLooper())
        val holdOn = Runnable { NativeBridge.setBypassGrade(true) }
        playerView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { holdHandler.postDelayed(holdOn, 200); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdHandler.removeCallbacks(holdOn)
                    NativeBridge.setBypassGrade(false); true
                }
                else -> false
            }
        }
        playerArea.addView(Button(this).apply {
            text = "📊"; alpha = 0.75f
            setOnClickListener { scopesView.cycleMode() }
        }, FrameLayout.LayoutParams(wc(), wc(), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(playerArea, LinearLayout.LayoutParams(mpv(), 0, 1f))

        // ---- Hang 1: chon video + 5 preset (dong bo slider tu JSON) ----
        val presetRow = LinearLayout(this)
        presetRow.addView(Button(this).apply {
            text = "🎬"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        }, rowLp(0.8f))
        listOf("PQ", "Cyan", "Deep", "Indo", "Soc").forEachIndexed { i, name ->
            presetRow.addView(Button(this).apply {
                text = name
                setOnClickListener { applyPreset(i) }
            }, rowLp(1f))
        }
        root.addView(presetRow)

        // ---- Hang 2: Copy/Paste Attributes + Keyframing ----
        val opsRow = LinearLayout(this)
        opsRow.addView(Button(this).apply {
            text = "⧉ Copy"
            setOnClickListener {
                val uri = currentUri ?: return@setOnClickListener toast("Chua co clip")
                if (gradeManager.copyAttributes(uri)) toast("Da copy 3 layer ra clipboard")
            }
        }, rowLp(1f))
        opsRow.addView(Button(this).apply {
            text = "⇶ Paste All"
            setOnClickListener {
                if (!gradeManager.hasClipboard()) return@setOnClickListener toast("Clipboard trong")
                val targets = gallery.selectedClips().ifEmpty { gallery.allClips().toSet() }
                val n = gradeManager.pasteToAll(targets, currentUri)
                toast("Da ap grade cho $n clip")
            }
        }, rowLp(1f))
        opsRow.addView(Button(this).apply {
            text = "◧ KF dau"
            setOnClickListener { keyframes.setStartKeyframe(sliders.grade); toast("KF dau da ghi") }
        }, rowLp(1f))
        opsRow.addView(Button(this).apply {
            text = "◨ KF cuoi"
            setOnClickListener { keyframes.setEndKeyframe(sliders.grade); toast("KF cuoi da ghi") }
        }, rowLp(1f))
        opsRow.addView(Button(this).apply {
            text = "KF ▶"
            setOnClickListener {
                if (!keyframes.hasBoth()) return@setOnClickListener toast("Can du 2 keyframe")
                keyframes.enabled = !keyframes.enabled
                text = if (keyframes.enabled) "KF ■" else "KF ▶"
                toast(if (keyframes.enabled) "Keyframing BAT — grade doi theo tien trinh clip"
                      else "Keyframing tat")
            }
        }, rowLp(1f))
        root.addView(opsRow)

        // ---- Task E4: LUT LIBRARY (an/hien bang nut 🎨 o hang preset) ----
        lutRepo = com.freedive.colorapp.lut.LutRepository(this)
        lutLibrary = com.freedive.colorapp.lut.LutLibraryView(this).apply {
            visibility = android.view.View.GONE
            onImportClick = {
                pickLuts.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            }
            onLutSelected = { path ->
                sliders.grade.lutPath = path               // vao GradeState -> JSON/draft
                currentUri?.let { gradeManager.put(it, sliders.grade) }
                toast("LUT: ${java.io.File(path).nameWithoutExtension}")
            }
        }
        presetRow.addView(Button(this).apply {
            text = "🎨"
            setOnClickListener {
                lutLibrary.visibility = if (lutLibrary.visibility == android.view.View.GONE)
                    android.view.View.VISIBLE else android.view.View.GONE
            }
        }, rowLp(0.8f))
        root.addView(lutLibrary)

        // ---- Task E1/E2/E3: HANG TUY CHON XUAT (codec/fps/slow-mo/audio/logo) ----
        val optRow = LinearLayout(this)
        fun optToggle(on: String, off: String, checked: Boolean) =
            android.widget.ToggleButton(this).apply {
                textOn = on; textOff = off; isChecked = checked
            }
        optAvc = optToggle("H.264 8-bit", "HEVC 10-bit", false)
        optFps60 = optToggle("60 fps", "FPS nguon", false)
        optSlowMo = optToggle("🐢 Slow-Mo x2", "Toc do thuong", false)
        optMute = optToggle("🔇 Mute", "🔊 Audio goc", true)
        optWatermark = optToggle("💧 Logo BAT", "Logo", false)
        optWatermark.setOnCheckedChangeListener { _, v ->
            if (v) ensureWatermarkLoaded()
            NativeBridge.setWatermarkEnabled(v)            // xem truoc ngay trong preview
        }
        listOf(optAvc, optFps60, optSlowMo, optMute, optWatermark).forEach {
            optRow.addView(it, rowLp(1f))
        }
        root.addView(optRow)

        // ---- Hang 3: XUAT FILE (Task 4.1/4.3) + progress ----
        batchExporter = BatchExporter(this, gradeManager)
        val exportRow = LinearLayout(this)
        exportRow.addView(Button(this).apply {
            text = "⇪ Xuat clip nay"
            setOnClickListener {
                val uri = currentUri ?: return@setOnClickListener toast("Chua co clip")
                startExport(listOf(uri))
            }
        }, rowLp(1f))
        exportRow.addView(Button(this).apply {
            text = "⇪ Xuat TAT CA"
            setOnClickListener {
                val clips = gallery.allClips()
                if (clips.isEmpty()) return@setOnClickListener toast("Gallery trong")
                startExport(clips)
            }
        }, rowLp(1f))
        root.addView(exportRow)
        exportBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = android.view.View.GONE
        }
        root.addView(exportBar)

        // ---- Gallery clip (Task 3.2) ----
        gallery = ClipGalleryView(this).apply {
            onClipSelected = { uri -> selectClip(uri) }
        }
        root.addView(gallery)

        // ---- Panel slider ----
        sliders = ColorSliders(this).apply {
            onUserTouch = { scopesView.onColorSliderTouched() }
            onGradeChanged = { g ->
                currentUri?.let { gradeManager.put(it, g) }
                draftStore.scheduleSave(gallery.allClips(), gradeManager.snapshot())  // Task 5.2
                smartGuide.onGradeChanged(g)                                          // Task S1
            }
            onProGuideToggle = { on -> smartGuide.setProMode(on) }                    // Task S1
        }
        // Task S1 — Smart Guide: goi y slider ke tiep tren panel (View-based, QD14)
        smartGuide.onStateChanged = { st -> runOnUiThread { sliders.setGuideState(st) } }
        sliders.setGuideState(smartGuide.state.value)     // trang thai khoi dau (buoc 1)
        root.addView(ScrollView(this).apply { addView(sliders) },
            LinearLayout.LayoutParams(mpv(), (resources.displayMetrics.heightPixels * 0.38f).toInt()))

        setContentView(root)

        // TASK 5.2 — khoi phuc draft (ke ca sau khi app bi kill)
        draftStore.load()?.let { (clips, grades) ->
            gradeManager.restore(grades)
            clips.forEach { gallery.addClip(it) }
            if (clips.isNotEmpty()) toast("Da khoi phuc ${clips.size} clip tu phien truoc")
        }
    }

    override fun onPause() {
        draftStore.saveNow(gallery.allClips(), gradeManager.snapshot())   // Task 5.2
        super.onPause()
    }

    /** Preset bam tu hang nut: nap gia tri JSON vao slider + renderer */
    private fun applyPreset(index: Int) {
        NativeBridge.setPreset(index)
        val states = runCatching { PresetLoader.states(this) }.getOrNull()
        val g = states?.getOrNull(index) ?: return
        // Ban sao doc lap de slider chinh khong pha cache preset
        val copy = com.freedive.colorapp.grade.GradeState.fromJson(g.toJson())
        sliders.setGrade(copy, pushAll = false)   // renderer da o che do preset
        currentUri?.let { gradeManager.put(it, copy) }
        smartGuide.onGradeChanged(copy)           // Task S1 — guide bam theo preset
    }

    /** Chon clip trong gallery: phat + ap grade rieng cua clip */
    private fun selectClip(uri: Uri) {
        currentUri = uri
        val g = gradeManager.stateFor(uri)
        sliders.setGrade(g, pushAll = true)
        lutLibrary.setSourceClip(uri)          // Task E4 — thumbnail live theo clip moi
        startPlayback(uri)
    }

    /** Task E1/E2/E3 — dong goi lua chon tren options bar thanh ExportConfig */
    private fun buildExportConfig() = com.freedive.colorapp.export.ExportConfig(
        codec = if (optAvc.isChecked) com.freedive.colorapp.export.ExportCodec.AVC_8BIT
                else com.freedive.colorapp.export.ExportCodec.HEVC_MAIN10,
        fpsOverride = if (optFps60.isChecked) 60 else null,
        speed = if (optSlowMo.isChecked) com.freedive.colorapp.export.ExportSpeed.SLOWMO_50
                else com.freedive.colorapp.export.ExportSpeed.NORMAL,
        muteAudio = optMute.isChecked || optSlowMo.isChecked,   // slow-mo ep mute
        watermark = optWatermark.isChecked,
    )

    /**
     * Task E3 — nap logo watermark 1 lan: uu tien assets/watermark.png; neu khong
     * co thi TU VE logo chu "FREEDIVE COLOR" nen mo tren Bitmap (khong can asset).
     */
    private fun ensureWatermarkLoaded() {
        if (watermarkLoaded) return
        val bmp = runCatching {
            assets.open("watermark.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: run {
            val w = 512; val h = 128
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            val bg = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(90, 6, 12, 20); isAntiAlias = true
            }
            cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 24f, 24f, bg)
            val p = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(235, 235, 243, 250)
                textSize = 56f; isAntiAlias = true
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            }
            cv.drawText("FREEDIVE COLOR", 28f, 82f, p)
            b
        }
        val argb = android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width, bmp.height, true)
            .copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val px = IntArray(argb.width * argb.height)
        argb.getPixels(px, 0, argb.width, 0, 0, argb.width, argb.height)
        val rgba = ByteArray(px.size * 4)
        px.forEachIndexed { i, c ->
            rgba[i * 4]     = ((c shr 16) and 0xFF).toByte()   // R (ARGB -> RGBA)
            rgba[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()    // G
            rgba[i * 4 + 2] = (c and 0xFF).toByte()            // B
            rgba[i * 4 + 3] = ((c shr 24) and 0xFF).toByte()   // A
        }
        watermarkLoaded = NativeBridge.setWatermarkImage(rgba, argb.width, argb.height)
        if (!watermarkLoaded) toast("Nap logo loi: ${NativeBridge.lastError()}")
    }

    private fun startPlayback(uri: Uri) {
        engine?.release()
        keyframes.enabled = false
        val e = MediaCodecEngine(this)
        if (!e.open(uri)) return toast("File khong co track HEVC")
        if (!e.isMain10) toast("Canh bao: video khong phai Main10 (D-Log M?)")
        keyframes.setDuration(e.durationUs)
        e.onFrameTime = { pts -> keyframes.onFrame(pts) }   // noi suy grade theo do sau
        engine = e
        if (playerView.holder.surface?.isValid == true) e.start()
        else playerView.onSurfaceReady = { e.start() }
    }

    /** Task 4: dung preview (encoder can renderer doc quyen) roi chay batch nen */
    private fun startExport(clips: List<Uri>) {
        if (batchExporter.running) return toast("Dang xuat — cho xong da")
        batchExporter.config = buildExportConfig()          // Task E1/E2/E3 — tu options bar
        engine?.release(); engine = null                    // preview dung lai
        exportBar.visibility = android.view.View.VISIBLE
        batchExporter.onProgress = { i, n, pct ->
            runOnUiThread {
                exportBar.progress = pct
                title = "Xuat clip $i/$n — $pct%"
            }
        }
        batchExporter.onFinished = { files ->
            runOnUiThread {
                exportBar.visibility = android.view.View.GONE
                title = "Freediving Color"
                toast("Xuat xong ${files.size} clip vao thu muc Movies cua app")
                currentUri?.let { selectClip(it) }          // phat lai preview
            }
        }
        batchExporter.exportAll(clips)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun rowLp(w: Float) = LinearLayout.LayoutParams(0, wc(), w)
    private fun mp() = FrameLayout.LayoutParams(mpv(), mpv())
    private fun mpv() = LinearLayout.LayoutParams.MATCH_PARENT
    private fun wc() = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onDestroy() {
        engine?.release()
        gallery.releaseThreads()
        super.onDestroy()
    }
}
