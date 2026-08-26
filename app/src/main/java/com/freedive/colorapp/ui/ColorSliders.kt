// ============================================================================
//  TASK 2.3 + 3.5 + S1/S2 — COLOR SLIDERS · panel dieu khien grade
//  Tuan 3: slider dong bo hai chieu voi GradeState + HSL ISOLATION.
//  Bo sung S1 — SMART GUIDE (View-based, QD14):
//    · Moi slider nam trong container co vien glow; slider duoc goi y se
//      DAP SANG theo nhip (ValueAnimator 700ms dao nguoc) + doi mau tieu de.
//    · Cong tac "Pro Guide" (MaterialSwitch) + dong goi y buoc hien tai.
//    · Logic goi y nam o guide/SmartGuideManager.kt (StateFlow, khong dinh UI).
//  Bo sung S2 — SKIN-TONE LOCK MASK: nhom dieu khien mask khoa tone da
//  (bat/tat, Hue muc tieu, Tolerance, Feather, Strength, Xem mask).
//  Haptic tick khi slider qua moc 0 (spec Precision Sliders).
// ============================================================================
package com.freedive.colorapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import com.freedive.colorapp.NativeBridge
import com.freedive.colorapp.grade.GradeState
import com.freedive.colorapp.guide.GuideKeys
import com.freedive.colorapp.guide.GuideState
import com.freedive.colorapp.guide.GuideStep
import com.google.android.material.materialswitch.MaterialSwitch

class ColorSliders(context: Context) : LinearLayout(context) {

    /** ScopesPopupView dung cho smart opacity */
    var onUserTouch: (() -> Unit)? = null
    /** MainActivity nghe de luu grade cua clip hien tai */
    var onGradeChanged: ((GradeState) -> Unit)? = null
    /** MainActivity noi voi SmartGuideManager.setProMode */
    var onProGuideToggle: ((Boolean) -> Unit)? = null

    var grade = GradeState()
        private set

    /** Xem mask tone da (debug view — KHONG thuoc GradeState, reset khi doi clip) */
    private var skinMaskView = false

    private data class Row(val box: LinearLayout, val glow: GradientDrawable,
                           val bar: SeekBar, val title: TextView, val label: String,
                           val min: Float, val max: Float, val guideKey: String?,
                           val get: () -> Float, val set: (Float) -> Unit,
                           val push: () -> Unit)
    private val rows = mutableListOf<Row>()
    private var binding = false          // chan push khi dang nap gia tri vao UI

    private val eyeButtons = mutableListOf<ToggleButton>()
    private lateinit var skinProtectBtn: ToggleButton
    private lateinit var skinLockBtn: ToggleButton
    private lateinit var skinMaskViewBtn: ToggleButton

    // ---- Smart Guide (S1) ----
    private lateinit var guideSwitch: MaterialSwitch
    private lateinit var guideHint: TextView
    private var guideTargets: Set<String> = emptySet()
    private val pulse = ValueAnimator.ofFloat(0.25f, 1f).apply {
        duration = 700
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { an ->
            val a = an.animatedValue as Float
            val stroke = Color.argb((a * 200).toInt(), 57, 135, 229)   // accent Ocean
            rows.forEach { r ->
                if (r.guideKey != null && r.guideKey in guideTargets) {
                    r.glow.setStroke(dp(1) + 1, stroke)
                    r.title.setTextColor(Color.rgb(
                        (255 - (255 - 57) * a * 0.5f).toInt(),
                        (255 - (255 - 135) * a * 0.5f).toInt(), 255))
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(235, 18, 20, 24))
        setPadding(dp(14), dp(10), dp(14), dp(10))

        // --- Pro Guide (S1): switch + goi y buoc ke tiep ---
        val guideRow = LinearLayout(context)
        guideSwitch = MaterialSwitch(context).apply {
            text = "Pro Guide"
            isChecked = true
            setOnCheckedChangeListener { _, v -> if (!binding) onProGuideToggle?.invoke(v) }
        }
        guideRow.addView(guideSwitch, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(guideRow)
        guideHint = TextView(context).apply {
            setTextColor(Color.rgb(127, 178, 240)); textSize = 11f
            setPadding(0, 0, 0, dp(4))
        }
        addView(guideHint)

        // --- Eye-toggle 3 layer ---
        val toggles = LinearLayout(context)
        fun eye(label: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            ToggleButton(context).apply {
                textOn = "👁 $label"; textOff = "🚫 $label"; isChecked = get()
                setOnCheckedChangeListener { _, v ->
                    if (!binding) { set(v); pushToggles(); notifyChanged() }
                }
                eyeButtons += this
            }
        toggles.addView(eye("L1 Pre", { grade.l1On }, { grade.l1On = it }), lp())
        toggles.addView(eye("L2 LUT", { grade.l2On }, { grade.l2On = it }), lp())
        toggles.addView(eye("L3 Post", { grade.l3On }, { grade.l3On = it }), lp())
        addView(toggles)

        // --- Anti-Green ---
        slider("Anti-Green", 0f, 1f, { grade.antiGreen },
               { grade.antiGreen = it }, key = GuideKeys.ANTI_GREEN) {
            NativeBridge.setAntiGreen(grade.antiGreen)
        }

        // --- Layer 1 ---
        header("LAYER 1 · PRE-LUT")
        slider("Nhiet do mau (Temp)", -1f, 1f, { grade.temp }, { grade.temp = it },
               key = GuideKeys.TEMP, push = ::pushL1)
        slider("Sac thai (Tint)", -1f, 1f, { grade.tint }, { grade.tint = it },
               key = GuideKeys.TINT, push = ::pushL1)
        slider("Phoi sang (EV)", -2f, 2f, { grade.ev }, { grade.ev = it },
               key = GuideKeys.EV, push = ::pushL1)
        slider("Tuong phan", -1f, 1f, { grade.contrast }, { grade.contrast = it }, push = ::pushL1)
        slider("Shadows", -1f, 1f, { grade.shadows }, { grade.shadows = it },
               key = GuideKeys.SHADOWS, push = ::pushL1)
        slider("Highlights", -1f, 1f, { grade.highlights }, { grade.highlights = it }, push = ::pushL1)
        slider("Red Recovery", 0f, 1f, { grade.redRecovery }, { grade.redRecovery = it },
               key = GuideKeys.RED_RECOVERY, push = ::pushL1)

        // --- Layer 2 ---
        header("LAYER 2 · LUT ENGINE")
        slider("LUT Mix (Intensity)", 0f, 1f, { grade.lutIntensity },
               { grade.lutIntensity = it }, key = GuideKeys.LUT_MIX) {
            NativeBridge.setLutIntensity(grade.lutIntensity)
        }

        // --- Layer 3: HSL ISOLATION (Core Features #3) ---
        header("SKIN · ORANGE/RED (tach tho lan khoi nen nuoc)")
        skinProtectBtn = ToggleButton(context).apply {
            textOn = "🔒 Skin Tone Protection BAT"; textOff = "Skin Tone Protection"
            setOnCheckedChangeListener { _, v ->
                if (!binding) { grade.skinProtect = v; pushL3(); notifyChanged() }
            }
        }
        addView(skinProtectBtn)
        // band 0 = Red, band 1 = Orange — slider chinh CA HAI cung luc
        bandPair("Hue da", -30f, 30f, 0, 1)
        bandPairSat("Sat da", 0, 1)
        bandPairLuma("Luma da", 0, 1)

        // --- S2: SKIN-TONE LOCK MASK (GPU) ---
        header("SKIN LOCK MASK · khoa tone da khoi chinh mau nen (GPU)")
        val lockRow = LinearLayout(context)
        skinLockBtn = ToggleButton(context).apply {
            textOn = "🎭 Skin Lock BAT"; textOff = "Skin Lock"
            setOnCheckedChangeListener { _, v ->
                if (!binding) { grade.skinLock = v; pushL3(); notifyChanged() }
            }
        }
        skinMaskViewBtn = ToggleButton(context).apply {
            textOn = "👁 Dang xem MASK"; textOff = "Xem mask"
            setOnCheckedChangeListener { _, v ->
                if (!binding) { skinMaskView = v; pushL3() }   // debug view — khong vao GradeState
            }
        }
        lockRow.addView(skinLockBtn, lp())
        lockRow.addView(skinMaskViewBtn, lp())
        addView(lockRow)
        slider("Hue da muc tieu (°)", 0f, 60f, { grade.skinLockHue },
               { grade.skinLockHue = it }, push = ::pushL3)
        slider("Tolerance (°)", 5f, 40f, { grade.skinLockTol },
               { grade.skinLockTol = it }, push = ::pushL3)
        slider("Feather (°)", 5f, 45f, { grade.skinLockFeather },
               { grade.skinLockFeather = it }, push = ::pushL3)
        slider("Strength", 0f, 1f, { grade.skinLockStrength },
               { grade.skinLockStrength = it }, push = ::pushL3)

        header("DEEP SEA · CYAN/BLUE (mau nuoc)")
        // band 4 = Cyan, band 5 = Blue
        bandPair("Hue nuoc", -30f, 30f, 4, 5, key = GuideKeys.HSL_WATER_HUE)
        bandPairSat("Sat nuoc", 4, 5, key = GuideKeys.HSL_WATER_SAT)
        bandPairLuma("Luma nuoc", 4, 5)

        header("TOAN CUC")
        slider("Global Saturation", -1f, 1f, { grade.globalSat },
               { grade.globalSat = it }, key = GuideKeys.GLOBAL_SAT, push = ::pushL3)
        slider("Clarity", -1f, 1f, { grade.clarity },
               { grade.clarity = it }) { NativeBridge.setClarity(grade.clarity) }
    }

    // ------------------------------------------------------------------------
    /** S1 — nhan trang thai guide tu MainActivity (SmartGuideManager.onStateChanged) */
    fun setGuideState(s: GuideState) {
        guideTargets = s.targets
        binding = true
        guideSwitch.isChecked = s.step != GuideStep.OFF
        binding = false
        guideHint.text = s.hint
        guideHint.visibility = if (s.hint.isEmpty()) GONE else VISIBLE
        // Tra ve trang thai thuong cho cac row khong con duoc goi y
        rows.forEach { r ->
            if (r.guideKey == null || r.guideKey !in s.targets) {
                r.glow.setStroke(dp(1), Color.argb(40, 255, 255, 255))
                r.title.setTextColor(Color.WHITE)
            }
        }
        if (s.targets.isEmpty()) { if (pulse.isRunning) pulse.cancel() }
        else if (!pulse.isRunning) pulse.start()
    }

    /** Nap grade moi (tu preset JSON hoac clip gallery) va cap nhat toan bo UI */
    fun setGrade(g: GradeState, pushAll: Boolean) {
        grade = g
        skinMaskView = false                       // debug view reset khi doi clip
        binding = true
        rows.forEach { r ->
            r.bar.progress = (((r.get() - r.min) / (r.max - r.min)) * 200f).toInt()
            r.title.text = "${r.label}: %.2f".format(r.get())
        }
        eyeButtons[0].isChecked = g.l1On
        eyeButtons[1].isChecked = g.l2On
        eyeButtons[2].isChecked = g.l3On
        skinProtectBtn.isChecked = g.skinProtect
        skinLockBtn.isChecked = g.skinLock
        skinMaskViewBtn.isChecked = false
        binding = false
        if (pushAll) g.applyTo()
    }

    private fun pushL1() = NativeBridge.setLayer1Params(
        grade.temp, grade.tint, grade.ev, grade.contrast,
        grade.shadows, grade.highlights, grade.redRecovery, grade.magentaGuard)
    private fun pushL3() = NativeBridge.setLayer3Params(grade.layer3Array(skinMaskView))
    private fun pushToggles() = NativeBridge.setLayerVisibility(grade.l1On, grade.l2On, grade.l3On)
    private fun notifyChanged() { onGradeChanged?.invoke(grade) }

    // Cap band dung chung mot slider
    private fun bandPair(label: String, min: Float, max: Float, i1: Int, i2: Int,
                         key: String? = null) =
        slider(label, min, max, { grade.hsl[i1].hue },
               { v -> grade.hsl[i1].hue = v; grade.hsl[i2].hue = v }, key = key, push = ::pushL3)
    private fun bandPairSat(label: String, i1: Int, i2: Int, key: String? = null) =
        slider(label, -1f, 1f, { grade.hsl[i1].sat },
               { v -> grade.hsl[i1].sat = v; grade.hsl[i2].sat = v }, key = key, push = ::pushL3)
    private fun bandPairLuma(label: String, i1: Int, i2: Int) =
        slider(label, -1f, 1f, { grade.hsl[i1].luma },
               { v -> grade.hsl[i1].luma = v; grade.hsl[i2].luma = v }, push = ::pushL3)

    private fun header(text: String) = addView(TextView(context).apply {
        this.text = text
        setTextColor(Color.rgb(137, 135, 129)); textSize = 10.5f
        setPadding(0, dp(10), 0, dp(2))
    })

    private fun slider(label: String, min: Float, max: Float,
                       get: () -> Float, set: (Float) -> Unit,
                       key: String? = null, push: () -> Unit) {
        // Container co vien bo tron — nen cho hieu ung glow cua Smart Guide
        val glow = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.argb(40, 255, 255, 255))
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = glow
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val title = TextView(context).apply {
            text = "$label: %.2f".format(get())
            setTextColor(Color.WHITE); textSize = 12f
        }
        box.addView(title)
        val bar = SeekBar(context).apply {
            this.max = 200
            progress = (((get() - min) / (max - min)) * 200f).toInt()
            var lastSign = Integer.signum(get().compareTo(0f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 200f
                    title.text = "$label: %.2f".format(v)
                    if (fromUser && !binding) {
                        onUserTouch?.invoke()
                        val sign = Integer.signum(v.compareTo(0f))
                        if (sign != lastSign && (sign == 0 || lastSign != 0)) performHaptic()
                        lastSign = sign
                        set(v); push(); notifyChanged()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) { onUserTouch?.invoke() }
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(bar)
        val lpBox = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lpBox.setMargins(0, dp(1), 0, dp(1))
        addView(box, lpBox)
        rows += Row(box, glow, bar, title, label, min, max, key, get, set, push)
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()                    // khong ro ri animator khi panel bi go
        super.onDetachedFromWindow()
    }

    private fun performHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
}
