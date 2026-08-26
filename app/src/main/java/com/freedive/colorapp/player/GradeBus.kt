// ============================================================================
//  GRADE BUS — kenh truyen tham so tu UI (main thread) sang shader (GL thread)
//  Slider chay tren main thread, shader doc tren GL thread cua Media3.
//  GradeState la data class co the bi sua tai cho, nen moi lan day ta LUU MOT
//  BAN CHUP PHANG (FloatArray) — GL thread doc mang bat bien, khong can khoa.
// ============================================================================
package com.freedive.colorapp.player

import com.freedive.colorapp.grade.GradeState

object GradeBus {

    /** Chi so trong mang uniform phang */
    object I {
        const val TEMP = 0; const val TINT = 1; const val EV = 2; const val CONTRAST = 3
        const val SHADOWS = 4; const val HIGHLIGHTS = 5; const val RED_RECOVERY = 6
        const val ANTI_GREEN = 7; const val MAGENTA_GUARD = 8; const val L1_ON = 9
        const val LUT_INTENSITY = 10; const val L2_ON = 11
        const val GLOBAL_SAT = 12; const val SKIN_PROTECT = 13; const val L3_ON = 14
        const val SHADOW_TINT_R = 15; const val SHADOW_TINT_G = 16; const val SHADOW_TINT_B = 17
        const val SKIN_HUE = 18; const val SKIN_TOL = 19; const val SKIN_FEATHER = 20
        const val SKIN_STRENGTH = 21; const val SKIN_ENABLE = 22; const val SKIN_MASK_VIEW = 23
        const val CLARITY = 24
        const val HSL_BASE = 25          // 7 band x 3 = 21 phan tu (25..45)
        const val COUNT = 46
    }

    @Volatile
    var params: FloatArray = flatten(GradeState(), maskView = false)
        private set

    @Volatile
    var lutPath: String = ""
        private set

    /** Goi tu main thread moi khi grade doi */
    fun push(g: GradeState, maskView: Boolean = false) {
        params = flatten(g, maskView)
        lutPath = g.lutPath
    }

    private fun flatten(g: GradeState, maskView: Boolean): FloatArray {
        val a = FloatArray(I.COUNT)
        a[I.TEMP] = g.temp
        a[I.TINT] = g.tint
        a[I.EV] = g.ev
        a[I.CONTRAST] = g.contrast
        a[I.SHADOWS] = g.shadows
        a[I.HIGHLIGHTS] = g.highlights
        a[I.RED_RECOVERY] = g.redRecovery
        a[I.ANTI_GREEN] = g.antiGreen
        a[I.MAGENTA_GUARD] = if (g.magentaGuard) 1f else 0f
        a[I.L1_ON] = if (g.l1On) 1f else 0f
        a[I.LUT_INTENSITY] = g.lutIntensity
        a[I.L2_ON] = if (g.l2On && g.lutPath.isNotEmpty()) 1f else 0f
        a[I.GLOBAL_SAT] = g.globalSat
        a[I.SKIN_PROTECT] = if (g.skinProtect) 1f else 0f
        a[I.L3_ON] = if (g.l3On) 1f else 0f
        a[I.SHADOW_TINT_R] = g.shadowTint.getOrElse(0) { 0f }
        a[I.SHADOW_TINT_G] = g.shadowTint.getOrElse(1) { 0f }
        a[I.SHADOW_TINT_B] = g.shadowTint.getOrElse(2) { 0f }
        a[I.SKIN_HUE] = g.skinLockHue
        a[I.SKIN_TOL] = g.skinLockTol
        a[I.SKIN_FEATHER] = g.skinLockFeather
        a[I.SKIN_STRENGTH] = g.skinLockStrength
        a[I.SKIN_ENABLE] = if (g.skinLock) 1f else 0f
        a[I.SKIN_MASK_VIEW] = if (maskView) 1f else 0f
        a[I.CLARITY] = g.clarity
        for (i in 0 until 7) {
            val b = g.hsl.getOrNull(i)
            a[I.HSL_BASE + i * 3] = b?.hue ?: 0f
            a[I.HSL_BASE + i * 3 + 1] = b?.sat ?: 0f
            a[I.HSL_BASE + i * 3 + 2] = b?.luma ?: 0f
        }
        return a
    }
}
