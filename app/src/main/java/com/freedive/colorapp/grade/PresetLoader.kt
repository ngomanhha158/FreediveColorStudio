// ============================================================================
//  TASK 3.5 — PRESET LOADER · doc assets/freediving_color_presets.json
//  -> GradeState de DONG BO SLIDER voi preset dang chon (ton dong Tuan 2).
//  Nguon su that la file JSON sinh tu spec — khong hard-code lai gia tri.
// ============================================================================
package com.freedive.colorapp.grade

import android.content.Context
import org.json.JSONObject

object PresetLoader {

    private const val ASSET = "freediving_color_presets.json"
    private val bandOrder = listOf("red", "orange", "yellow", "green", "cyan", "blue", "magenta")

    private var cache: List<GradeState>? = null
    private var antiGreenRecommended: Map<Int, Float> = emptyMap()

    fun states(context: Context): List<GradeState> {
        cache?.let { return it }
        val root = JSONObject(
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        )
        // Muc Anti-Green khoi diem de xuat theo preset (global_controls)
        val rec = mutableMapOf<Int, Float>()
        root.optJSONObject("global_controls")?.optJSONObject("anti_green")
            ?.optJSONObject("recommended_per_preset")?.let { r ->
                val ids = listOf("phu_quoc_deep_emerald", "tropical_cyan",
                                 "true_deep_sea_blue", "indo_cinematic_moody", "social_media_vibrant")
                ids.forEachIndexed { i, id -> rec[i] = r.optDouble(id, 0.0).toFloat() }
            }
        antiGreenRecommended = rec

        val list = mutableListOf<GradeState>()
        val presets = root.getJSONArray("presets")
        for (i in 0 until presets.length()) {
            val p = presets.getJSONObject(i)
            val g = GradeState()
            p.optJSONObject("pre_lut")?.let { pre ->
                g.temp = pre.optDouble("white_balance_temperature").f()
                g.tint = pre.optDouble("tint").f()
                g.ev = pre.optDouble("exposure_ev").f()
                g.contrast = pre.optDouble("contrast").f()
                g.shadows = pre.optDouble("shadows_lift").f()
                g.highlights = pre.optDouble("highlights").f()
                g.redRecovery = pre.optDouble("red_channel_recovery").f()
                g.magentaGuard = pre.optBoolean("magenta_shadow_guard")
            }
            p.optJSONObject("lut_engine")?.let { g.lutIntensity = it.optDouble("intensity", 1.0).toFloat() }
            p.optJSONObject("post_lut")?.let { post ->
                g.globalSat = post.optDouble("global_saturation").f()
                g.clarity = post.optDouble("clarity").f()
                g.skinProtect = post.optBoolean("skin_tone_protection")
                post.optJSONArray("shadow_tint_rgb")?.let { st ->
                    for (k in 0 until minOf(3, st.length())) g.shadowTint[k] = st.getDouble(k).toFloat()
                }
                post.optJSONObject("hsl")?.let { hsl ->
                    bandOrder.forEachIndexed { bi, name ->
                        hsl.optJSONObject(name)?.let { b ->
                            g.hsl[bi] = HslBand(b.optDouble("hue_deg").f(),
                                                b.optDouble("saturation").f(),
                                                b.optDouble("luma").f())
                        }
                    }
                }
            }
            g.antiGreen = antiGreenRecommended[i] ?: 0f
            list += g
        }
        cache = list
        return list
    }

    private fun Double.f() = if (isNaN()) 0f else toFloat()
}
