// ============================================================================
//  PARSER .cube (Kotlin) — cho duong GL ES
//  Ban Vulkan doc .cube o C++ (lut_parser.cpp). Duong Media3/GL can du lieu
//  nam o phia Kotlin de upload thanh GL_TEXTURE_3D, nen co parser rieng nay.
//  Ho tro: LUT_3D_SIZE, DOMAIN_MIN/MAX, chu thich '#', dong trong.
//  Thu tu du lieu .cube: R bien thien NHANH NHAT -> khop layout texture 3D
//  (x = R, y = G, z = B) khi nap tuan tu.
// ============================================================================
package com.freedive.colorapp.player

import android.util.Log
import java.io.File

data class CubeLut(val size: Int, val rgb: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is CubeLut && other.size == size && other.rgb.contentEquals(rgb)
    override fun hashCode(): Int = 31 * size + rgb.contentHashCode()
}

object CubeLutParser {

    private const val TAG = "FDC/CubeLut"

    /** Tra ve null neu file hong hoac khong phai LUT 3D hop le */
    fun parse(path: String): CubeLut? {
        val f = File(path)
        if (!f.isFile) return null
        return runCatching {
            var size = 0
            var domainMin = 0f
            var domainMax = 1f
            val values = ArrayList<Float>(33 * 33 * 33 * 3)

            f.forEachLine { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachLine
                when {
                    line.startsWith("LUT_3D_SIZE", true) ->
                        size = line.split(Regex("\\s+"))[1].toInt()
                    line.startsWith("LUT_1D_SIZE", true) ->
                        throw IllegalArgumentException("LUT 1D khong duoc ho tro")
                    line.startsWith("DOMAIN_MIN", true) ->
                        domainMin = line.split(Regex("\\s+"))[1].toFloat()
                    line.startsWith("DOMAIN_MAX", true) ->
                        domainMax = line.split(Regex("\\s+"))[1].toFloat()
                    line.startsWith("TITLE", true) -> Unit
                    else -> {
                        val p = line.split(Regex("\\s+"))
                        if (p.size >= 3) {
                            values += p[0].toFloat()
                            values += p[1].toFloat()
                            values += p[2].toFloat()
                        }
                    }
                }
            }

            if (size < 2) throw IllegalArgumentException("Thieu LUT_3D_SIZE")
            val expected = size * size * size * 3
            if (values.size != expected)
                throw IllegalArgumentException("So dong du lieu sai: ${values.size / 3}, can ${expected / 3}")

            val arr = FloatArray(expected)
            val span = if (domainMax - domainMin != 0f) domainMax - domainMin else 1f
            for (i in 0 until expected) {
                arr[i] = ((values[i] - domainMin) / span).coerceIn(0f, 1f)
            }
            CubeLut(size, arr)
        }.onFailure { Log.e(TAG, "Doc LUT that bai (${f.name}): ${it.message}") }
            .getOrNull()
    }
}
