// ============================================================================
//  DEPTH-BASED KEYFRAMING (Core Features #4) · noi suy grade theo tien trinh clip
//  Kich ban: tho lan di len tu 25m -> mat nuoc; Red Recovery can giam dan
//  (vd 0.80 o day -> 0.10 tren mat). Nguoi dung dat keyframe DAU va CUOI clip
//  bang trang thai grade hien tai; khi phat, moi frame decoder bao
//  presentationTimeUs -> lerp GradeState -> applyTo(renderer).
//  Noi suy CPU-side (mirror fdc::Evaluate) — shader/UBO khong doi.
// ============================================================================
package com.freedive.colorapp.grade

class KeyframeController {

    private var kfStart: GradeState? = null
    private var kfEnd: GradeState? = null
    private var durationUs: Long = 0

    /** Bat/tat noi suy (co keyframe day du moi co tac dung) */
    var enabled = false

    fun setDuration(us: Long) { durationUs = us }

    /** Ghi keyframe DAU clip = ban sao trang thai grade hien tai */
    fun setStartKeyframe(current: GradeState) {
        kfStart = GradeState.fromJson(current.toJson())
    }

    /** Ghi keyframe CUOI clip */
    fun setEndKeyframe(current: GradeState) {
        kfEnd = GradeState.fromJson(current.toJson())
    }

    fun hasBoth(): Boolean = kfStart != null && kfEnd != null

    fun clear() { kfStart = null; kfEnd = null; enabled = false }

    /**
     * Goi tu decoder moi frame (presentationTimeUs). Tra ve state da noi suy
     * va DA AP xuong renderer; null neu keyframing khong hoat dong.
     * Luu y: applyTo goi cac setter JNI nhe (chi ghi struct C++), GPU nhan
     * gia tri o frame ke tiep — khong co stall.
     */
    fun onFrame(presentationTimeUs: Long): GradeState? {
        if (!enabled || durationUs <= 0) return null
        val a = kfStart ?: return null
        val b = kfEnd ?: return null
        val t = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
        val g = GradeState.lerp(a, b, t)
        g.applyTo()
        return g
    }
}
