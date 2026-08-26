// ============================================================================
//  TASK 3.3/3.4 — GRADE STATE · toan bo tham so 3 layer cua mot clip
//  Serialize ra JSON (Copy Attributes -> file cuc bo), ap nguoc vao renderer
//  (Paste Attributes / chuyen clip trong gallery), va NOI SUY duoc giua 2 state
//  (Depth-based Keyframing — mirror fdc::Evaluate phia C++).
// ============================================================================
package com.freedive.colorapp.grade

import com.freedive.colorapp.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Mot band HSL (hueDeg, sat, luma) */
data class HslBand(var hue: Float = 0f, var sat: Float = 0f, var luma: Float = 0f)

data class GradeState(
    // Layer 1
    var temp: Float = 0f, var tint: Float = 0f, var ev: Float = 0f,
    var contrast: Float = 0f, var shadows: Float = 0f, var highlights: Float = 0f,
    var redRecovery: Float = 0f, var magentaGuard: Boolean = false,
    var antiGreen: Float = 0f,
    // Layer 2
    var lutIntensity: Float = 1f, var lutPath: String = "",
    // Layer 3 — 7 band R/O/Y/G/C/B/M
    var hsl: Array<HslBand> = Array(7) { HslBand() },
    var globalSat: Float = 0f,
    var clarity: Float = 0f,          // Task 4 — unsharp luma o composite
    var shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    var skinProtect: Boolean = false,
    // Task S2 — Skin-Tone Lock Mask (GPU): khoa tone da khoi chinh Layer 3
    var skinLock: Boolean = false,
    var skinLockHue: Float = 25f,        // hue da muc tieu (do) — orange/red
    var skinLockTol: Float = 18f,        // vung loi bao ve tron ven (do)
    var skinLockFeather: Float = 22f,    // vung chuyen smoothstep (do)
    var skinLockStrength: Float = 1f,    // 0..1 — muc do khoa
    // Eye toggles
    var l1On: Boolean = true, var l2On: Boolean = true, var l3On: Boolean = true,
) {
    /** Ap toan bo state xuong renderer (dung khi Paste / doi clip / keyframe tick) */
    fun applyTo(bridge: NativeBridge = NativeBridge) {
        bridge.setLayer1Params(temp, tint, ev, contrast, shadows, highlights, redRecovery, magentaGuard)
        bridge.setAntiGreen(antiGreen)
        bridge.setLutIntensity(lutIntensity)
        bridge.setLayer3Params(layer3Array())
        bridge.setClarity(clarity)
        bridge.setLayerVisibility(l1On, l2On, l3On)
        if (lutPath.isNotEmpty()) bridge.loadLutFromPath(lutPath)
    }

    /**
     * Mang tham so Layer 3 cho JNI — 32 float (26 cu + 6 Skin Lock Mask):
     * [26]=lock bat · [27]=hue° · [28]=tol° · [29]=feather° · [30]=strength
     * [31]=maskView (debug, KHONG thuoc state — truyen rieng tu ColorSliders)
     */
    fun layer3Array(maskView: Boolean = false): FloatArray {
        val a = FloatArray(32)
        hsl.forEachIndexed { i, b -> a[i*3] = b.hue; a[i*3+1] = b.sat; a[i*3+2] = b.luma }
        a[21] = globalSat
        a[22] = shadowTint[0]; a[23] = shadowTint[1]; a[24] = shadowTint[2]
        a[25] = if (skinProtect) 1f else 0f
        a[26] = if (skinLock) 1f else 0f
        a[27] = skinLockHue; a[28] = skinLockTol; a[29] = skinLockFeather
        a[30] = skinLockStrength
        a[31] = if (maskView) 1f else 0f
        return a
    }

    // ---------------------------- JSON --------------------------------------
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("l1", JSONObject().apply {
            put("temp", temp); put("tint", tint); put("ev", ev); put("contrast", contrast)
            put("shadows", shadows); put("highlights", highlights)
            put("redRecovery", redRecovery); put("magentaGuard", magentaGuard)
            put("antiGreen", antiGreen)
        })
        put("l2", JSONObject().apply { put("intensity", lutIntensity); put("lutPath", lutPath) })
        put("l3", JSONObject().apply {
            put("hsl", JSONArray().apply {
                hsl.forEach { b -> put(JSONArray().apply { put(b.hue); put(b.sat); put(b.luma) }) }
            })
            put("globalSat", globalSat)
            put("clarity", clarity)
            put("shadowTint", JSONArray().apply { shadowTint.forEach { put(it) } })
            put("skinProtect", skinProtect)
            put("skinLock", JSONObject().apply {
                put("on", skinLock); put("hue", skinLockHue); put("tol", skinLockTol)
                put("feather", skinLockFeather); put("strength", skinLockStrength)
            })
        })
        put("eyes", JSONArray().apply { put(l1On); put(l2On); put(l3On) })
    }

    fun saveTo(file: File) = file.writeText(toJson().toString(2))

    companion object {
        fun fromJson(o: JSONObject): GradeState {
            val g = GradeState()
            o.optJSONObject("l1")?.let { l1 ->
                g.temp = l1.optDouble("temp").toFloatOr0(); g.tint = l1.optDouble("tint").toFloatOr0()
                g.ev = l1.optDouble("ev").toFloatOr0(); g.contrast = l1.optDouble("contrast").toFloatOr0()
                g.shadows = l1.optDouble("shadows").toFloatOr0()
                g.highlights = l1.optDouble("highlights").toFloatOr0()
                g.redRecovery = l1.optDouble("redRecovery").toFloatOr0()
                g.magentaGuard = l1.optBoolean("magentaGuard")
                g.antiGreen = l1.optDouble("antiGreen").toFloatOr0()
            }
            o.optJSONObject("l2")?.let { l2 ->
                g.lutIntensity = l2.optDouble("intensity", 1.0).toFloat()
                g.lutPath = l2.optString("lutPath")
            }
            o.optJSONObject("l3")?.let { l3 ->
                l3.optJSONArray("hsl")?.let { arr ->
                    for (i in 0 until minOf(7, arr.length())) {
                        val b = arr.getJSONArray(i)
                        g.hsl[i] = HslBand(b.getDouble(0).toFloat(),
                                           b.getDouble(1).toFloat(), b.getDouble(2).toFloat())
                    }
                }
                g.globalSat = l3.optDouble("globalSat").toFloatOr0()
                g.clarity = l3.optDouble("clarity").toFloatOr0()
                l3.optJSONArray("shadowTint")?.let { st ->
                    for (i in 0 until minOf(3, st.length())) g.shadowTint[i] = st.getDouble(i).toFloat()
                }
                g.skinProtect = l3.optBoolean("skinProtect")
                l3.optJSONObject("skinLock")?.let { sl ->
                    g.skinLock = sl.optBoolean("on")
                    g.skinLockHue = sl.optDouble("hue", 25.0).toFloat()
                    g.skinLockTol = sl.optDouble("tol", 18.0).toFloat()
                    g.skinLockFeather = sl.optDouble("feather", 22.0).toFloat()
                    g.skinLockStrength = sl.optDouble("strength", 1.0).toFloat()
                }
            }
            o.optJSONArray("eyes")?.let { e ->
                if (e.length() >= 3) { g.l1On = e.getBoolean(0); g.l2On = e.getBoolean(1); g.l3On = e.getBoolean(2) }
            }
            return g
        }

        fun loadFrom(file: File): GradeState? =
            runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()

        /** Noi suy tuyen tinh — mirror fdc::Evaluate (bool giu gia tri keyframe dau) */
        fun lerp(a: GradeState, b: GradeState, t: Float): GradeState {
            fun f(x: Float, y: Float) = x + (y - x) * t
            return GradeState(
                temp = f(a.temp, b.temp), tint = f(a.tint, b.tint), ev = f(a.ev, b.ev),
                contrast = f(a.contrast, b.contrast), shadows = f(a.shadows, b.shadows),
                highlights = f(a.highlights, b.highlights),
                redRecovery = f(a.redRecovery, b.redRecovery),
                magentaGuard = a.magentaGuard,
                antiGreen = f(a.antiGreen, b.antiGreen),
                lutIntensity = f(a.lutIntensity, b.lutIntensity), lutPath = a.lutPath,
                hsl = Array(7) { i ->
                    HslBand(f(a.hsl[i].hue, b.hsl[i].hue),
                            f(a.hsl[i].sat, b.hsl[i].sat),
                            f(a.hsl[i].luma, b.hsl[i].luma))
                },
                globalSat = f(a.globalSat, b.globalSat),
                clarity = f(a.clarity, b.clarity),
                shadowTint = floatArrayOf(f(a.shadowTint[0], b.shadowTint[0]),
                                          f(a.shadowTint[1], b.shadowTint[1]),
                                          f(a.shadowTint[2], b.shadowTint[2])),
                skinProtect = a.skinProtect,
                skinLock = a.skinLock,
                skinLockHue = f(a.skinLockHue, b.skinLockHue),
                skinLockTol = f(a.skinLockTol, b.skinLockTol),
                skinLockFeather = f(a.skinLockFeather, b.skinLockFeather),
                skinLockStrength = f(a.skinLockStrength, b.skinLockStrength),
                l1On = a.l1On, l2On = a.l2On, l3On = a.l3On,
            )
        }

        private fun Double.toFloatOr0() = if (isNaN()) 0f else toFloat()
    }
}
