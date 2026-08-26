// ============================================================================
//  MAIN ACTIVITY — man hinh grade (GIAI DOAN 1 cua viec chuyen sang Media3)
//
//  Duong hinh anh MOI:
//      ExoPlayer -> DefaultVideoFrameProcessor (GL ES)
//                -> GradeGlEffect  (CST + Layer 1 + LUT + Layer 3)
//                -> ClarityGlEffect
//                -> SurfaceView trong ComposeView
//  Tham so mau di qua GradeBus (UI ghi -> GL thread doc), nen keo slider la
//  doi ngay ca khi video dang tam dung — Media3 ve lai khung hien tai.
//
//  CON LAI TREN DUONG VULKAN (chua chuyen): xuat file (BatchExporter),
//  Scopes (compute shader) va watermark preview. Xem ghi chu o startExport().
// ============================================================================
package com.freedive.colorapp

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.util.UnstableApi
import com.freedive.colorapp.export.BatchExporter
import com.freedive.colorapp.grade.DraftStore
import com.freedive.colorapp.grade.GradeManager
import com.freedive.colorapp.grade.KeyframeController
import com.freedive.colorapp.grade.PresetLoader
import com.freedive.colorapp.guide.SmartGuideManager
import com.freedive.colorapp.player.GradeBus
import com.freedive.colorapp.player.GradePlayer
import com.freedive.colorapp.ui.ClipGalleryView
import com.freedive.colorapp.ui.ColorSliders
import com.freedive.colorapp.ui.L
import com.freedive.colorapp.ui.Theme
import com.freedive.colorapp.ui.compose.GradePlayerScreen

@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var gradePlayer: GradePlayer
    private lateinit var sliders: ColorSliders
    private val smartGuide = SmartGuideManager()
    private lateinit var gallery: ClipGalleryView
    private lateinit var gradeManager: GradeManager
    private lateinit var draftStore: DraftStore
    private lateinit var batchExporter: BatchExporter
    private lateinit var exportBar: ProgressBar
    private lateinit var lutRepo: com.freedive.colorapp.lut.LutRepository
    private lateinit var lutLibrary: com.freedive.colorapp.lut.LutLibraryView

    // ---- Tuy chon xuat (gom vao menu ⋮) ----
    private var optAvc = false
    private var optFps60 = false
    private var optSlowMo = false
    private var optMute = true
    private var optWatermark = false
    private lateinit var exportSummary: TextView

    private val presetChips = mutableListOf<TextView>()
    private lateinit var kfPlayChip: TextView

    private val keyframes = KeyframeController()
    private var currentUri: Uri? = null

    // Keyframing: truoc day nhan tick tu decoder. Nay hoi vi tri cua ExoPlayer.
    private val kfHandler = Handler(Looper.getMainLooper())
    private val kfTick = object : Runnable {
        override fun run() {
            // Thoi luong chi biet duoc sau khi ExoPlayer prepare xong, nen cap
            // nhat o day thay vi ngay luc mo clip.
            val dur = gradePlayer.player.duration
            if (dur > 0) keyframes.setDuration(dur * 1000L)
            if (keyframes.enabled && dur > 0 && gradePlayer.player.isPlaying) {
                keyframes.onFrame(gradePlayer.player.currentPosition * 1000L)
                    ?.let { GradeBus.push(it) }
            }
            kfHandler.postDelayed(this, 40)
        }
    }

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

    private val pickLuts =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val imported = lutRepo.import(uris)
            toast(if (imported.isEmpty())
                      L.t("Không nhập được file .cube hợp lệ", "No valid .cube file imported")
                  else L.t("Đã nhập ${imported.size} LUT vào thư viện",
                           "Imported ${imported.size} LUT(s)"))
            lutLibrary.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        gradeManager = GradeManager(this)
        draftStore = DraftStore(this)
        gradePlayer = GradePlayer(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }

        // ============ 1. PLAYER (Compose: mat phat + dieu khien) ============
        val playerHost = ComposeView(this).apply {
            setContent { GradePlayerScreen(gradePlayer, Modifier.fillMaxSize()) }
        }
        root.addView(playerHost, LinearLayout.LayoutParams(mpv(), 0, 1f))

        // ============ 2. KHU DIEU KHIEN ============
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 8),
                       Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 4))
        }

        controls.addView(Theme.sectionLabel(this, L.t("Preset màu", "Color presets")))
        val (presetScroll, presetRow) = Theme.strip(this)
        presetRow.addView(Theme.chip(this, L.t("🎬  Chọn video", "🎬  Pick video")) {
            pickVideo.launch(arrayOf("video/*"))
        }, Theme.gapLp(this))
        listOf("PQ", "Cyan", "Deep", "Indo", L.t("Sốc", "Punch")).forEachIndexed { i, name ->
            val ch = Theme.chip(this, name) { applyPreset(i) }
            presetChips += ch
            presetRow.addView(ch, Theme.gapLp(this))
        }
        presetRow.addView(Theme.chip(this, L.t("🎨  Thư viện LUT", "🎨  LUT library")) {
            lutLibrary.visibility =
                if (lutLibrary.visibility == View.GONE) View.VISIBLE else View.GONE
        }, Theme.gapLp(this))
        presetRow.addView(Theme.chip(this, L.switchLabel()) {
            L.set(this, !L.isVi)
            recreate()
        }, Theme.gapLp(this))
        controls.addView(presetScroll)

        controls.addView(Theme.sectionLabel(this,
            L.t("Sao chép & Keyframe", "Copy & Keyframe")))
        val (toolScroll, toolRow) = Theme.strip(this)
        toolRow.addView(Theme.chip(this, L.t("⧉  Copy grade", "⧉  Copy grade")) {
            val uri = currentUri ?: return@chip toast(L.t("Chưa có clip", "No clip loaded"))
            if (gradeManager.copyAttributes(uri))
                toast(L.t("Đã copy 3 layer ra clipboard", "Copied all 3 layers to clipboard"))
        }, Theme.gapLp(this))
        toolRow.addView(Theme.chip(this, L.t("⇶  Dán hàng loạt", "⇶  Paste to all")) {
            if (!gradeManager.hasClipboard())
                return@chip toast(L.t("Clipboard trống", "Clipboard is empty"))
            val targets = gallery.selectedClips().ifEmpty { gallery.allClips().toSet() }
            val n = gradeManager.pasteToAll(targets, currentUri)
            toast(L.t("Đã áp grade cho $n clip", "Applied grade to $n clip(s)"))
        }, Theme.gapLp(this))
        toolRow.addView(Theme.chip(this, L.t("◧  KF đầu", "◧  Start KF")) {
            keyframes.setStartKeyframe(sliders.grade)
            toast(L.t("Đã ghi keyframe đầu", "Start keyframe saved"))
        }, Theme.gapLp(this))
        toolRow.addView(Theme.chip(this, L.t("◨  KF cuối", "◨  End KF")) {
            keyframes.setEndKeyframe(sliders.grade)
            toast(L.t("Đã ghi keyframe cuối", "End keyframe saved"))
        }, Theme.gapLp(this))
        kfPlayChip = Theme.chip(this, L.t("▶  Bật keyframe", "▶  Enable keyframes"),
            accentWhenOn = Theme.WARN) {
            if (!keyframes.hasBoth())
                return@chip toast(L.t("Cần đủ 2 keyframe", "Need both keyframes"))
            keyframes.enabled = !keyframes.enabled
            kfPlayChip.text = if (keyframes.enabled) L.t("■  Tắt keyframe", "■  Disable keyframes")
                              else L.t("▶  Bật keyframe", "▶  Enable keyframes")
            Theme.setChipOn(this, kfPlayChip, keyframes.enabled, Theme.WARN)
            toast(if (keyframes.enabled)
                      L.t("Keyframing BẬT — grade đổi theo tiến trình clip",
                          "Keyframing ON — grade follows clip progress")
                  else L.t("Keyframing tắt", "Keyframing off"))
        }
        toolRow.addView(kfPlayChip, Theme.gapLp(this))
        controls.addView(toolScroll)
        root.addView(controls)

        // ---- Thu vien LUT ----
        lutRepo = com.freedive.colorapp.lut.LutRepository(this)
        lutLibrary = com.freedive.colorapp.lut.LutLibraryView(this).apply {
            visibility = View.GONE
            onImportClick = {
                pickLuts.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            }
            onLutSelected = { path ->
                sliders.grade.lutPath = path
                currentUri?.let { gradeManager.put(it, sliders.grade) }
                GradeBus.push(sliders.grade)          // GL thread nap lai texture 3D
                toast("LUT: ${java.io.File(path).nameWithoutExtension}")
            }
        }
        root.addView(lutLibrary)

        // ============ 3. GALLERY ============
        gallery = ClipGalleryView(this).apply { onClipSelected = { uri -> selectClip(uri) } }
        root.addView(gallery)

        // ============ 4. PANEL SLIDER ============
        sliders = ColorSliders(this).apply {
            onUserTouch = { }
            onGradeChanged = { g ->
                currentUri?.let { gradeManager.put(it, g) }
                draftStore.scheduleSave(gallery.allClips(), gradeManager.snapshot())
                smartGuide.onGradeChanged(g)
                GradeBus.push(g)                      // -> shader GL, ap ngay
            }
            onProGuideToggle = { on -> smartGuide.setProMode(on) }
        }
        smartGuide.onStateChanged = { st -> runOnUiThread { sliders.setGuideState(st) } }
        sliders.setGuideState(smartGuide.state.value)
        root.addView(ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(sliders)
        }, LinearLayout.LayoutParams(mpv(), 0, 1.25f))

        // ============ 5. THANH DUOI ============
        exportBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        root.addView(exportBar)

        batchExporter = BatchExporter(this, gradeManager)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 8),
                       Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 12))
        }
        exportSummary = TextView(this).apply {
            typeface = Theme.MONO
            textSize = 11f
            setTextColor(Theme.TEXT_DIM)
            setPadding(0, 0, 0, Theme.dp(this@MainActivity, 8))
        }
        bottom.addView(exportSummary)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Theme.primary(this, L.t("⇪  Xuất clip này", "⇪  Export this clip")) {
            val uri = currentUri ?: return@primary toast(L.t("Chưa có clip", "No clip loaded"))
            startExport(listOf(uri))
        }, LinearLayout.LayoutParams(0, wc(), 1f))
        actionRow.addView(View(this), LinearLayout.LayoutParams(Theme.dp(this, 8), 1))
        val moreBtn = Theme.ghost(this, "⋮") { }
        moreBtn.setOnClickListener { showMoreMenu(moreBtn) }
        actionRow.addView(moreBtn, LinearLayout.LayoutParams(wc(), wc()))
        bottom.addView(actionRow)
        root.addView(bottom)

        updateExportSummary()
        setContentView(root)
        kfHandler.post(kfTick)

        draftStore.load()?.let { (clips, grades) ->
            gradeManager.restore(grades)
            clips.forEach { gallery.addClip(it) }
            if (clips.isNotEmpty())
                toast(L.t("Đã khôi phục ${clips.size} clip từ phiên trước",
                          "Restored ${clips.size} clip(s) from last session"))
        }
    }

    // ------------------------------------------------------------------------
    private fun showMoreMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val mn = m.menu
        mn.add(0, 1, 0, if (optAvc) "Codec: H.264 8-bit" else "Codec: HEVC 10-bit")
        mn.add(0, 2, 1, if (optFps60) L.t("Khung hình: 60 fps", "Frame rate: 60 fps")
                        else L.t("Khung hình: theo nguồn", "Frame rate: source"))
        mn.add(0, 3, 2, if (optSlowMo) L.t("Tốc độ: Slow-Mo ×2", "Speed: Slow-Mo ×2")
                        else L.t("Tốc độ: thường", "Speed: normal"))
        mn.add(0, 4, 3, L.t("Tắt tiếng", "Mute audio"))
            .setCheckable(true).setChecked(optMute)
        mn.add(0, 5, 4, L.t("Đóng logo chìm", "Watermark logo"))
            .setCheckable(true).setChecked(optWatermark)
        mn.add(0, 6, 5, L.t("⇪  Xuất TẤT CẢ clip", "⇪  Export ALL clips"))
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> optAvc = !optAvc
                2 -> optFps60 = !optFps60
                3 -> optSlowMo = !optSlowMo
                4 -> optMute = !optMute
                5 -> optWatermark = !optWatermark
                6 -> {
                    val clips = gallery.allClips()
                    if (clips.isEmpty()) toast(L.t("Gallery trống", "Gallery is empty"))
                    else startExport(clips)
                }
            }
            updateExportSummary()
            true
        }
        m.show()
    }

    private fun updateExportSummary() {
        val parts = mutableListOf(
            if (optAvc) "H.264 8-bit" else "HEVC 10-bit",
            if (optFps60) "60 fps" else L.t("FPS nguồn", "source fps"),
        )
        if (optSlowMo) parts += "Slow-Mo x2"
        if (optMute || optSlowMo) parts += L.t("Tắt tiếng", "muted")
        if (optWatermark) parts += "Logo"
        exportSummary.text = parts.joinToString("  ·  ")
    }

    private fun applyPreset(index: Int) {
        val states = runCatching { PresetLoader.states(this) }.getOrNull()
        val g = states?.getOrNull(index) ?: return
        val copy = com.freedive.colorapp.grade.GradeState.fromJson(g.toJson())
        sliders.setGrade(copy, pushAll = false)
        currentUri?.let { gradeManager.put(it, copy) }
        smartGuide.onGradeChanged(copy)
        GradeBus.push(copy)
        presetChips.forEachIndexed { i, ch -> Theme.setChipOn(this, ch, i == index) }
    }

    private fun selectClip(uri: Uri) {
        currentUri = uri
        val g = gradeManager.stateFor(uri)
        sliders.setGrade(g, pushAll = false)
        GradeBus.push(g)
        lutLibrary.setSourceClip(uri)
        keyframes.enabled = false
        kfPlayChip.text = L.t("▶  Bật keyframe", "▶  Enable keyframes")
        Theme.setChipOn(this, kfPlayChip, false, Theme.WARN)
        gradePlayer.open(uri, autoPlay = true)   // thoi luong keyframe cap nhat o kfTick
    }

    private fun buildExportConfig() = com.freedive.colorapp.export.ExportConfig(
        codec = if (optAvc) com.freedive.colorapp.export.ExportCodec.AVC_8BIT
                else com.freedive.colorapp.export.ExportCodec.HEVC_MAIN10,
        fpsOverride = if (optFps60) 60 else null,
        speed = if (optSlowMo) com.freedive.colorapp.export.ExportSpeed.SLOWMO_50
                else com.freedive.colorapp.export.ExportSpeed.NORMAL,
        muteAudio = optMute || optSlowMo,
        watermark = optWatermark,
    )

    /**
     * GIAI DOAN 1: duong xuat file van dua tren VulkanRenderer, ma renderer do
     * chi khoi tao khi co VideoPlayerView (da go khoi man hinh). Vi vay xuat
     * file TAM THOI khong dung duoc — se thay bang Media3 Transformer o giai
     * doan 2, dung chung GradeGlEffect/ClarityGlEffect voi preview nen mau
     * xuat ra khop tuyet doi voi mau dang xem.
     */
    private fun startExport(clips: List<Uri>) {
        toast(L.t("Xuất file đang được chuyển sang Media3 Transformer — chưa dùng được ở bản này",
                  "Export is being migrated to Media3 Transformer — not available in this build"))
    }

    override fun onPause() {
        gradePlayer.player.playWhenReady = false
        draftStore.saveNow(gallery.allClips(), gradeManager.snapshot())
        super.onPause()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun mpv() = LinearLayout.LayoutParams.MATCH_PARENT
    private fun wc() = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onDestroy() {
        kfHandler.removeCallbacks(kfTick)
        gradePlayer.release()
        gallery.releaseThreads()
        super.onDestroy()
    }
}
