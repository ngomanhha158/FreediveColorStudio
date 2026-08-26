// ============================================================================
//  COLOR SLIDERS — panel dieu khien grade (giao dien lam lai theo Theme "Abyss")
//  Thay doi so voi ban truoc:
//    · Moi nhom la mot SECTION GAP/MO duoc (▾/▸) — khong con do het 30 slider
//      ra man hinh cung luc.
//    · Nhan va GIA TRI nam cung mot dong: nhan ben trai, so ben phai font mono.
//    · Toggle dung chip bo tron thay ToggleButton xam mac dinh.
//  Giu nguyen: Smart Guide (S1), Skin Lock Mask (S2), haptic khi qua moc 0,
//  dong bo hai chieu voi GradeState.
// ============================================================================
package com.freedive.colorapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.freedive.colorapp.NativeBridge
import com.freedive.colorapp.grade.GradeState
import com.freedive.colorapp.guide.GuideKeys
import com.freedive.colorapp.guide.GuideState
import com.freedive.colorapp.guide.GuideStep
import com.google.android.material.materialswitch.MaterialSwitch

class ColorSliders(context: Context) : LinearLayout(context) {

    var onUserTouch: (() -> Unit)? = null
    var onGradeChanged: ((GradeState) -> Unit)? = null
    var onProGuideToggle: ((Boolean) -> Unit)? = null

    var grade = GradeState()
        private set

    /** Xem mask tone da (debug view — KHONG thuoc GradeState) */
    private var skinMaskView = false

    private data class Row(val box: LinearLayout, val glow: GradientDrawable,
                           val bar: SeekBar, val title: TextView, val value: TextView,
                           val label: String, val min: Float, val max: Float,
                           val guideKey: String?,
                           val get: () -> Float, val set: (Float) -> Unit,
                           val push: () -> Unit)
    private val rows = mutableListOf<Row>()
    private var binding = false

    private val eyeChips = mutableListOf<TextView>()
    private lateinit var skinProtectChip: TextView
    private lateinit var skinLockChip: TextView
    private lateinit var skinMaskChip: TextView

    /** Section dang mo — cac slider tao sau se roi vao day */
    private var currentBody: LinearLayout? = null

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
            val stroke = Color.argb((a * 210).toInt(), 46, 155, 214)
            rows.forEach { r ->
                if (r.guideKey != null && r.guideKey in guideTargets) {
                    r.glow.setStroke(dp(1) + 1, stroke)
                    r.title.setTextColor(Theme.ACCENT)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        setPadding(dp(12), dp(6), dp(12), dp(20))

        // --- Pro Guide (S1) ---
        val guideCard = Theme.card(context)
        guideSwitch = MaterialSwitch(context).apply {
            text = L.t("Pro Guide — gợi ý bước grade tiếp theo", "Pro Guide — suggests your next move")
            textSize = 13f
            setTextColor(Theme.TEXT)
            isChecked = true
            setOnCheckedChangeListener { _, v -> if (!binding) onProGuideToggle?.invoke(v) }
        }
        guideCard.addView(guideSwitch,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        guideHint = TextView(context).apply {
            setTextColor(Theme.ACCENT); textSize = 11.5f
            setPadding(0, dp(6), 0, 0)
        }
        guideCard.addView(guideHint)
        addView(guideCard, cardLp())

        // --- Eye-toggle 3 layer ---
        addView(Theme.sectionLabel(context, L.t("Bật/tắt lớp xử lý", "Layer visibility")))
        val (eyeScroll, eyeRow) = Theme.strip(context)
        fun eye(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val ch = Theme.chip(context, label, checkable = true, checked = get()) { v ->
                if (!binding) { set(Theme.chipIsOn(v)); pushToggles(); notifyChanged() }
            }
            eyeChips += ch
            eyeRow.addView(ch, Theme.gapLp(context))
        }
        eye(L.t("L1 · Pre-LUT", "L1 · Pre-LUT"), { grade.l1On }, { grade.l1On = it })
        eye(L.t("L2 · LUT", "L2 · LUT"), { grade.l2On }, { grade.l2On = it })
        eye(L.t("L3 · Post", "L3 · Post"), { grade.l3On }, { grade.l3On = it })
        addView(eyeScroll)

        // --- Anti-Green (luon hien, khong nam trong section gap duoc) ---
        currentBody = null
        slider(L.t("Anti-Green", "Anti-Green"), 0f, 1f, { grade.antiGreen },
               { grade.antiGreen = it }, key = GuideKeys.ANTI_GREEN) {
            NativeBridge.setAntiGreen(grade.antiGreen)
        }

        // --- Layer 1 ---
        section(L.t("Layer 1 · Pre-LUT", "Layer 1 · Pre-LUT"), expanded = true)
        slider(L.t("Nhiệt độ màu (Temp)", "Temperature"), -1f, 1f, { grade.temp }, { grade.temp = it },
               key = GuideKeys.TEMP, push = ::pushL1)
        slider(L.t("Sắc thái (Tint)", "Tint"), -1f, 1f, { grade.tint }, { grade.tint = it },
               key = GuideKeys.TINT, push = ::pushL1)
        slider(L.t("Phơi sáng (EV)", "Exposure (EV)"), -2f, 2f, { grade.ev }, { grade.ev = it },
               key = GuideKeys.EV, push = ::pushL1)
        slider(L.t("Tương phản", "Contrast"), -1f, 1f, { grade.contrast }, { grade.contrast = it }, push = ::pushL1)
        slider(L.t("Vùng tối (Shadows)", "Shadows"), -1f, 1f, { grade.shadows }, { grade.shadows = it },
               key = GuideKeys.SHADOWS, push = ::pushL1)
        slider(L.t("Vùng sáng (Highlights)", "Highlights"), -1f, 1f, { grade.highlights },
               { grade.highlights = it }, push = ::pushL1)
        slider(L.t("Phục hồi đỏ (Red Recovery)", "Red Recovery"), 0f, 1f, { grade.redRecovery },
               { grade.redRecovery = it }, key = GuideKeys.RED_RECOVERY, push = ::pushL1)

        // --- Layer 2 ---
        section(L.t("Layer 2 · LUT Engine", "Layer 2 · LUT Engine"), expanded = true)
        slider(L.t("Độ trộn LUT (Intensity)", "LUT Mix (Intensity)"), 0f, 1f, { grade.lutIntensity },
               { grade.lutIntensity = it }, key = GuideKeys.LUT_MIX) {
            NativeBridge.setLutIntensity(grade.lutIntensity)
        }

        // --- Layer 3: HSL ISOLATION ---
        section(L.t("Tông da · Orange/Red", "Skin tones · Orange/Red"), expanded = false)
        skinProtectChip = Theme.chip(context, L.t("🔒  Bảo vệ tông da", "🔒  Skin tone protection"),
            checkable = true, checked = false) { v ->
            if (!binding) { grade.skinProtect = Theme.chipIsOn(v); pushL3(); notifyChanged() }
        }
        addToBody(skinProtectChip, chipLp())
        bandPair(L.t("Hue da", "Skin hue"), -30f, 30f, 0, 1)
        bandPairSat(L.t("Sat da", "Skin sat"), 0, 1)
        bandPairLuma(L.t("Luma da", "Skin luma"), 0, 1)

        // --- S2: SKIN-TONE LOCK MASK ---
        section(L.t("Skin Lock Mask · khoá tông da (GPU)", "Skin Lock Mask · lock skin tones (GPU)"), expanded = false)
        val (lockScroll, lockRow) = Theme.strip(context)
        skinLockChip = Theme.chip(context, L.t("🎭  Bật Skin Lock", "🎭  Enable Skin Lock"),
            checkable = true, checked = false) { v ->
            if (!binding) { grade.skinLock = Theme.chipIsOn(v); pushL3(); notifyChanged() }
        }
        skinMaskChip = Theme.chip(context, L.t("👁  Xem mask", "👁  View mask"),
            checkable = true, checked = false, accentWhenOn = Theme.WARN) { v ->
            if (!binding) { skinMaskView = Theme.chipIsOn(v); pushL3() }
        }
        lockRow.addView(skinLockChip, Theme.gapLp(context))
        lockRow.addView(skinMaskChip, Theme.gapLp(context))
        addToBody(lockScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        slider(L.t("Hue da mục tiêu (°)", "Target skin hue (°)"), 0f, 60f, { grade.skinLockHue },
               { grade.skinLockHue = it }, push = ::pushL3)
        slider(L.t("Dung sai (Tolerance °)", "Tolerance (°)"), 5f, 40f, { grade.skinLockTol },
               { grade.skinLockTol = it }, push = ::pushL3)
        slider(L.t("Vờn biên (Feather °)", "Feather (°)"), 5f, 45f, { grade.skinLockFeather },
               { grade.skinLockFeather = it }, push = ::pushL3)
        slider(L.t("Cường độ", "Strength"), 0f, 1f, { grade.skinLockStrength },
               { grade.skinLockStrength = it }, push = ::pushL3)

        section(L.t("Nước sâu · Cyan/Blue", "Deep water · Cyan/Blue"), expanded = true)
        bandPair(L.t("Hue nước", "Water hue"), -30f, 30f, 4, 5, key = GuideKeys.HSL_WATER_HUE)
        bandPairSat(L.t("Sat nước", "Water sat"), 4, 5, key = GuideKeys.HSL_WATER_SAT)
        bandPairLuma(L.t("Luma nước", "Water luma"), 4, 5)

        section(L.t("Toàn cục", "Global"), expanded = true)
        slider(L.t("Độ bão hoà tổng (Saturation)", "Global Saturation"), -1f, 1f, { grade.globalSat },
               { grade.globalSat = it }, key = GuideKeys.GLOBAL_SAT, push = ::pushL3)
        slider(L.t("Độ nét (Clarity)", "Clarity"), -1f, 1f, { grade.clarity },
               { grade.clarity = it }) { NativeBridge.setClarity(grade.clarity) }
    }

    // ------------------------------------------------------------------------
    fun setGuideState(s: GuideState) {
        guideTargets = s.targets
        binding = true
        guideSwitch.isChecked = s.step != GuideStep.OFF
        binding = false
        guideHint.text = s.hint
        guideHint.visibility = if (s.hint.isEmpty()) GONE else VISIBLE
        rows.forEach { r ->
            if (r.guideKey == null || r.guideKey !in s.targets) {
                r.glow.setStroke(dp(1), Theme.STROKE)
                r.title.setTextColor(Theme.TEXT)
            }
        }
        if (s.targets.isEmpty()) { if (pulse.isRunning) pulse.cancel() }
        else if (!pulse.isRunning) pulse.start()
    }

    fun setGrade(g: GradeState, pushAll: Boolean) {
        grade = g
        skinMaskView = false
        binding = true
        rows.forEach { r ->
            r.bar.progress = (((r.get() - r.min) / (r.max - r.min)) * 200f).toInt()
            r.value.text = "%.2f".format(r.get())
        }
        Theme.setChipOn(context, eyeChips[0], g.l1On)
        Theme.setChipOn(context, eyeChips[1], g.l2On)
        Theme.setChipOn(context, eyeChips[2], g.l3On)
        Theme.setChipOn(context, skinProtectChip, g.skinProtect)
        Theme.setChipOn(context, skinLockChip, g.skinLock)
        Theme.setChipOn(context, skinMaskChip, false, Theme.WARN)
        binding = false
        if (pushAll) g.applyTo()
    }

    private fun pushL1() = NativeBridge.setLayer1Params(
        grade.temp, grade.tint, grade.ev, grade.contrast,
        grade.shadows, grade.highlights, grade.redRecovery, grade.magentaGuard)
    private fun pushL3() = NativeBridge.setLayer3Params(grade.layer3Array(skinMaskView))
    private fun pushToggles() = NativeBridge.setLayerVisibility(grade.l1On, grade.l2On, grade.l3On)
    private fun notifyChanged() { onGradeChanged?.invoke(grade) }

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

    // ------------------------------------------------------------------------
    /** Tao mot nhom gap/mo duoc; cac slider tao sau se nam trong nhom nay. */
    private fun section(title: String, expanded: Boolean) {
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = if (expanded) VISIBLE else GONE
        }
        val head = TextView(context).apply {
            text = (if (expanded) "▾  " else "▸  ") + title.uppercase()
            textSize = 10.5f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.TEXT_MUTED)
            setPadding(dp(2), dp(14), dp(2), dp(6))
            isClickable = true
            setOnClickListener {
                val open = body.visibility == VISIBLE
                body.visibility = if (open) GONE else VISIBLE
                text = (if (open) "▸  " else "▾  ") + title.uppercase()
            }
        }
        addView(head)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        currentBody = body
    }

    private fun addToBody(v: View, lp: LayoutParams) {
        val b = currentBody
        if (b != null) b.addView(v, lp) else addView(v, lp)
    }

    private fun slider(label: String, min: Float, max: Float,
                       get: () -> Float, set: (Float) -> Unit,
                       key: String? = null, push: () -> Unit) {
        val glow = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Theme.SURFACE)
            setStroke(dp(1), Theme.STROKE)
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = glow
            setPadding(dp(12), dp(8), dp(12), dp(2))
        }
        // Hang nhan: ten ben trai, gia tri mono ben phai
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = label
            maxLines = 1
            isSingleLine = true
            setTextColor(Theme.TEXT); textSize = 12.5f
        }
        val value = Theme.valueText(context).apply { text = "%.2f".format(get()) }
        head.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        box.addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val bar = SeekBar(context).apply {
            this.max = 200
            progress = (((get() - min) / (max - min)) * 200f).toInt()
            progressDrawable?.setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN)
            thumb?.setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN)
            var lastSign = Integer.signum(get().compareTo(0f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 200f
                    value.text = "%.2f".format(v)
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
        box.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val lpBox = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lpBox.setMargins(0, dp(3), 0, dp(3))
        addToBody(box, lpBox)
        rows += Row(box, glow, bar, title, value, label, min, max, key, get, set, push)
    }

    private fun cardLp() =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(6), 0, dp(2))
        }

    private fun chipLp() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(2), 0, dp(6))
        }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    private fun performHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
