// ============================================================================
//  JNI BRIDGE (Kotlin side) — khop native_bridge.cpp
// ============================================================================
package com.freedive.colorapp

import android.content.res.AssetManager
import android.hardware.HardwareBuffer
import android.view.Surface

object NativeBridge {
    init { System.loadLibrary("colorcore") }

    external fun surfaceCreated(surface: Surface, assetManager: AssetManager): Boolean
    external fun surfaceResized(width: Int, height: Int)
    external fun surfaceDestroyed()

    /** Frame 10-bit tu ImageReader — zero-copy sang Vulkan */
    external fun submitFrame(hardwareBuffer: HardwareBuffer): Boolean

    /**
     * Ve LAI frame decode gan nhat voi tham so grade hien tai.
     * Bat buoc khi player TAM DUNG: khong co frame moi nao den de kich hoat ve,
     * nen keo slider mau se khong doi gi neu khong goi ham nay.
     */
    external fun redraw(): Boolean

    external fun loadLutFromPath(path: String): Boolean
    external fun setPreset(index: Int)
    external fun setAntiGreen(strength: Float)
    external fun setLutIntensity(value: Float)      // -1f = dung intensity cua preset
    external fun setLayerVisibility(l1: Boolean, l2: Boolean, l3: Boolean)

    /** TASK 2.3 — slider Layer 1 ghi de preset */
    external fun setLayer1Params(
        temp: Float, tint: Float, ev: Float, contrast: Float,
        shadows: Float, highlights: Float, redRecovery: Float, magentaGuard: Boolean
    )

    /** TASK 2.4 — Floating Scopes: mode 0=OFF 1=Vectorscope 2=Waveform */
    external fun setScopeConfig(mode: Int, cx: Float, cy: Float, size: Float, opacity: Float)

    /**
     * TASK 3.1 — Layer 3 ghi de preset. Mang 26 float:
     * [0..20] 7 band HSL x (hueDeg, sat, luma) R/O/Y/G/C/B/M ·
     * [21] globalSat · [22..24] shadowTint RGB · [25] skinProtect
     */
    external fun setLayer3Params(values: FloatArray)

    /** TASK 4 — Clarity (unsharp luma o pass composite) */
    external fun setClarity(value: Float)

    /** TASK 5.3 — Before/After: true = xem anh goc (chi CST, tat het hieu ung) */
    external fun setBypassGrade(on: Boolean)

    /** TASK E3 — Watermark: RGBA8 (w*h*4 byte) tu BitmapFactory; ve goc phai-duoi */
    external fun setWatermarkImage(rgba: ByteArray, width: Int, height: Int): Boolean
    external fun setWatermarkEnabled(on: Boolean)

    /** TASK E4 — ap LUT .cube len Bitmap ARGB_8888 TAI CHO (thumbnail live) */
    external fun applyLutToBitmap(bitmap: android.graphics.Bitmap, cubePath: String): Boolean

    /** TASK 4.1 — Export: swapchain chuyen sang encoder input surface */
    external fun beginExport(encoderSurface: Surface, width: Int, height: Int): Boolean
    external fun submitExportFrame(hardwareBuffer: HardwareBuffer): Boolean
    external fun endExport()

    /** TASK 4.2 — giai phong cache import AHardwareBuffer (giua cac clip batch) */
    external fun clearAhbCache()

    external fun lastError(): String
}
