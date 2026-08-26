// ============================================================================
//  TASK S1 (spec "Fable Week 3 bo sung") — SMART GUIDE · may trang thai quy
//  trinh grade nuoc chuan: quan sat GradeState va goi y SLIDER KE TIEP.
//
//  Quy trinh 5 buoc (theo spec):
//    1. WB_TINT       : White Balance / Tint (Anti-Green) — trung hoa mau nuoc
//    2. EXPOSURE_COMP : CHI KICH HOAT khi Tint (Magenta) > 20% — bu Exposure
//                       + Shadows (day magenta lam anh toi/bet vung toi)
//    3. RED_RECOVERY  : phuc hoi kenh do da mat theo do sau
//    4. LUT           : vao Layer 2 — chon .cube / keo LUT Mix
//    5. HSL           : tinh chinh Cyan/Blue (mau nuoc) o Layer 3
//
//  Thiet ke:
//  · PURE KOTLIN + StateFlow — khong dinh UI, dung duoc cho ca View (app hien
//    tai, QD12) lan Compose (file tham chieu SmartGuideHighlight.kt).
//  · Trang thai = ham thuan tuy cua (GradeState, cac buoc da bo qua) — nguoi
//    dung xoa grade ve 0 thi guide tu quay lai buoc truoc, khong can reset.
//  · onStateChanged callback song song StateFlow de MainActivity (View, khong
//    keo them coroutine-lifecycle) dang ky truc tiep.
// ============================================================================
package com.freedive.colorapp.guide

import com.freedive.colorapp.grade.GradeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Cac buoc cua quy trinh. OFF = Pro Guide tat. DONE = da di het. */
enum class GuideStep { OFF, WB_TINT, EXPOSURE_COMP, RED_RECOVERY, LUT, HSL, DONE }

/** Dinh danh slider duoc goi y — ColorSliders map key nay sang row cua no. */
object GuideKeys {
    const val TEMP = "temp"; const val TINT = "tint"; const val ANTI_GREEN = "ag"
    const val EV = "ev"; const val SHADOWS = "shadows"
    const val RED_RECOVERY = "red"; const val LUT_MIX = "lutmix"
    const val HSL_WATER_HUE = "hsl_water_hue"; const val HSL_WATER_SAT = "hsl_water_sat"
    const val GLOBAL_SAT = "gsat"
}

data class GuideState(
    val step: GuideStep,
    /** Key cac slider can lam noi bat (glow/pulse) */
    val targets: Set<String>,
    /** Goi y ngan hien tren panel */
    val hint: String,
)

class SmartGuideManager {

    private val _state = MutableStateFlow(computeFor(GradeState(), proOn = true, skipped = emptySet()))
    /** Spec yeu cau StateFlow — nguon su that cho moi observer */
    val state: StateFlow<GuideState> = _state.asStateFlow()
    /** Duong tat cho View classic (MainActivity) — goi moi khi state doi */
    var onStateChanged: ((GuideState) -> Unit)? = null

    private var proOn = true
    private var last = GradeState()
    private val skipped = mutableSetOf<GuideStep>()

    /** Cong tac "Pro Guide" (spec: Switch bat/tat — tat la ngung moi highlight) */
    fun setProMode(on: Boolean) { proOn = on; recompute() }
    fun isProMode() = proOn

    /** Goi tu MainActivity moi khi grade doi (slider / preset / paste / keyframe) */
    fun onGradeChanged(g: GradeState) { last = g; recompute() }

    /** Bo qua buoc dang goi y (nguoi dung tu quyet) */
    fun skipCurrent() { skipped += _state.value.step; recompute() }

    /** Ve dau quy trinh (clip moi) */
    fun reset() { skipped.clear(); last = GradeState(); recompute() }

    private fun recompute() {
        val next = computeFor(last, proOn, skipped)
        if (next != _state.value) {
            _state.value = next
            onStateChanged?.invoke(next)
        }
    }

    companion object {
        private const val EPS = 0.02f          // nguong "da dung den slider"
        private const val TINT_MAGENTA = 0.20f // spec: Tint tang > 20% -> buoc bu sang

        /** Ham thuan tuy: (grade, pro, skipped) -> trang thai guide */
        fun computeFor(g: GradeState, proOn: Boolean, skipped: Set<GuideStep>): GuideState {
            if (!proOn) return GuideState(GuideStep.OFF, emptySet(), "")

            val wbDone = abs(g.temp) > EPS || abs(g.tint) > EPS || g.antiGreen > EPS ||
                         GuideStep.WB_TINT in skipped
            // Buoc 2 chi ton tai khi tint magenta vuot 20% (spec dieu kien dong)
            val compNeeded = g.tint > TINT_MAGENTA
            val compDone = !compNeeded || abs(g.ev) > EPS || abs(g.shadows) > EPS ||
                           GuideStep.EXPOSURE_COMP in skipped
            val redDone = g.redRecovery > EPS || GuideStep.RED_RECOVERY in skipped
            val lutDone = g.lutPath.isNotEmpty() || abs(g.lutIntensity - 1f) > EPS ||
                          GuideStep.LUT in skipped
            val hslDone = abs(g.hsl[4].hue) > EPS || abs(g.hsl[4].sat) > EPS ||
                          abs(g.hsl[5].hue) > EPS || abs(g.hsl[5].sat) > EPS ||
                          abs(g.globalSat) > EPS || GuideStep.HSL in skipped

            return when {
                !wbDone -> GuideState(
                    GuideStep.WB_TINT,
                    setOf(GuideKeys.TEMP, GuideKeys.TINT, GuideKeys.ANTI_GREEN),
                    "① Can bang trang truoc: keo Temp/Tint hoac bat Anti-Green de trung hoa mau nuoc")
                !compDone -> GuideState(
                    GuideStep.EXPOSURE_COMP,
                    setOf(GuideKeys.EV, GuideKeys.SHADOWS),
                    "② Tint magenta ${(g.tint * 100).toInt()}% > 20% — bu Exposure/Shadows de anh khong bet toi")
                !redDone -> GuideState(
                    GuideStep.RED_RECOVERY,
                    setOf(GuideKeys.RED_RECOVERY),
                    "③ Phuc hoi kenh do (Red Recovery) — mau da mat dan tu 5m tro xuong")
                !lutDone -> GuideState(
                    GuideStep.LUT,
                    setOf(GuideKeys.LUT_MIX),
                    "④ Vao Layer 2: chon LUT .cube va keo LUT Mix ve muc vua mat")
                !hslDone -> GuideState(
                    GuideStep.HSL,
                    setOf(GuideKeys.HSL_WATER_HUE, GuideKeys.HSL_WATER_SAT, GuideKeys.GLOBAL_SAT),
                    "⑤ Tinh chinh mau nuoc: Hue/Sat nhom Deep Sea (Cyan/Blue) + Global Saturation")
                else -> GuideState(GuideStep.DONE, emptySet(),
                    "✓ Quy trinh grade hoan tat — kiem tra lai bang Vectorscope truoc khi xuat")
            }
        }
    }
}
