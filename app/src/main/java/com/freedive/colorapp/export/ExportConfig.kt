// ============================================================================
//  TASK E1/E2 (spec "Fable Week 4 bo sung") — EXPORT CONFIG
//  Data class cau hinh xuat (spec 4.1 muc 3): codec, do phan giai toi da,
//  fps, bitrate DONG + cac tinh nang freediving (spec 4.3): Slow-Mo 50%,
//  Mute audio, Watermark.
// ============================================================================
package com.freedive.colorapp.export

/** Codec dau ra. HEVC_MAIN10 = chat luong goc 10-bit; AVC_8BIT = tuong thich toi da. */
enum class ExportCodec { HEVC_MAIN10, AVC_8BIT }

/** Toc do phat. SLOWMO_50: nguon 60fps phat o timestamp x2 -> slow-mo muot khong rot frame. */
enum class ExportSpeed(val ptsScale: Long) { NORMAL(1L), SLOWMO_50(2L) }

data class ExportConfig(
    val codec: ExportCodec = ExportCodec.HEVC_MAIN10,
    /** Khung gioi han dau ra — aspect giu nguyen, khong upscale qua nguon. Toi da 4K. */
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    /** null = giu fps nguon; spec cho chon 30 hoac 60 */
    val fpsOverride: Int? = null,
    /** Bitrate dong (spec: 50, 100 Mbps...). Mac dinh 60 Mbps cho 4K da grade. */
    val bitrateMbps: Int = 60,
    val speed: ExportSpeed = ExportSpeed.NORMAL,
    /**
     * true = bo hoan toan track audio (loai tieng bot khi lan — spec 4.3.2).
     * false = copy nguyen ven (passthrough) track audio goc vao MP4.
     * LUU Y: Slow-Mo luon ep mute — audio goc khong khop timeline x2.
     */
    val muteAudio: Boolean = true,
    /** Chen logo alpha goc phai-duoi (spec 4.3.3) — renderer ve bang Vulkan. */
    val watermark: Boolean = false,
) {
    /** (w, h) dau ra: fit trong maxWidth x maxHeight, giu aspect, chan (yeu cau encoder) */
    fun fitOutput(srcW: Int, srcH: Int): Pair<Int, Int> {
        val scale = minOf(maxWidth.toFloat() / srcW, maxHeight.toFloat() / srcH, 1f)
        val w = (srcW * scale).toInt()
        val h = (srcH * scale).toInt()
        return (w - w % 2) to (h - h % 2)          // encoder yeu cau kich thuoc chan
    }

    val bitrateBps: Int get() = bitrateMbps * 1_000_000
}
