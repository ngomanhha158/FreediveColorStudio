// ============================================================================
//  FABLE TASK 1.1/1.2 — JNI BRIDGE · noi Kotlin UI voi VulkanRenderer (C++)
//  Kotlin goi qua object NativeBridge (external fun) — 1 renderer/1 player view.
// ============================================================================
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <memory>
#include <mutex>
#include <vector>
#include <string>

#include "lut_parser.h"
#include "vulkan_renderer.h"

namespace {
std::unique_ptr<fdc::VulkanRenderer> gRenderer;   // 1 instance cho player chinh
std::mutex gRenderMutex;                          // submitFrame (decode thread) vs redraw (UI thread)
std::string gLastError;
jstring toJstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_surfaceCreated(
        JNIEnv* env, jobject /*thiz*/, jobject surface, jobject assetManager) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    gRenderer = std::make_unique<fdc::VulkanRenderer>();
    return gRenderer->init(window, assets, &gLastError) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_surfaceResized(
        JNIEnv*, jobject, jint width, jint height) {
    if (gRenderer) gRenderer->onSurfaceResized(uint32_t(width), uint32_t(height));
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_surfaceDestroyed(JNIEnv*, jobject) {
    gRenderer.reset();
}

// Frame 10-bit tu ImageReader (Kotlin: image.hardwareBuffer) — zero-copy
JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_submitFrame(
        JNIEnv* env, jobject, jobject hardwareBuffer) {
    std::lock_guard<std::mutex> lk(gRenderMutex);
    if (!gRenderer) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) { gLastError = "HardwareBuffer null"; return JNI_FALSE; }
    if (!gRenderer->submitDecodedFrame(ahb, &gLastError)) return JNI_FALSE;
    return gRenderer->renderFrame(&gLastError) ? JNI_TRUE : JNI_FALSE;
}

// Ve lai frame gan nhat — goi tu UI thread khi player dang tam dung va nguoi
// dung dang keo slider mau (khong co frame moi nao den de kich hoat ve).
JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_redraw(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRenderMutex);
    if (!gRenderer) return JNI_FALSE;
    return gRenderer->redraw(&gLastError) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_loadLutFromPath(
        JNIEnv* env, jobject, jstring path) {
    if (!gRenderer) return JNI_FALSE;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    fdc::CubeLut lut = fdc::LoadCubeLutFromFile(cpath, &gLastError);
    env->ReleaseStringUTFChars(path, cpath);
    if (!lut.valid()) return JNI_FALSE;
    return gRenderer->uploadLut(lut, &gLastError) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setPreset(JNIEnv*, jobject, jint idx) {
    if (gRenderer) gRenderer->setPreset(idx);
}
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setAntiGreen(JNIEnv*, jobject, jfloat v) {
    if (gRenderer) gRenderer->setAntiGreen(v);
}
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setLutIntensity(JNIEnv*, jobject, jfloat v) {
    if (gRenderer) gRenderer->setLutIntensityOverride(v);
}
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setLayerVisibility(
        JNIEnv*, jobject, jboolean l1, jboolean l2, jboolean l3) {
    if (gRenderer) gRenderer->setLayerVisibility(l1, l2, l3);
}
// TASK 2.3 — slider Layer 1 ghi de preset (temp, tint, ev, contrast, shadows,
// highlights, redRecovery); magentaGuard giu theo preset dang chon
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setLayer1Params(
        JNIEnv*, jobject, jfloat temp, jfloat tint, jfloat ev, jfloat contrast,
        jfloat shadows, jfloat highlights, jfloat redRecovery, jboolean magentaGuard) {
    if (!gRenderer) return;
    fdc::PreLutParams p{temp, tint, ev, contrast, shadows, highlights,
                        redRecovery, magentaGuard == JNI_TRUE};
    gRenderer->setLayer1Params(p);
}

// TASK 2.4 — cau hinh Floating Scopes (mode 0/1/2, vi tri/kich thuoc chuan hoa, opacity)
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setScopeConfig(
        JNIEnv*, jobject, jint mode, jfloat cx, jfloat cy, jfloat size, jfloat opacity) {
    if (gRenderer) gRenderer->setScopeConfig(mode, cx, cy, size, opacity);
}

// TASK 3.1 + S2 — Layer 3 ghi de preset. Mang 32 float (26 cu van hop le):
// [0..20]  = 7 band HSL x (hueDeg, sat, luma) theo thu tu R/O/Y/G/C/B/M
// [21]     = globalSat · [22..24] = shadowTint RGB · [25] = skinProtect (>0.5 = bat)
// [26]     = skinLock bat · [27] = hue muc tieu (do) · [28] = tolerance (do)
// [29]     = feather (do) · [30] = strength 0..1 · [31] = maskView (debug)
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setLayer3Params(
        JNIEnv* env, jobject, jfloatArray arr) {
    if (!gRenderer) return;
    const jsize n = env->GetArrayLength(arr);
    if (n < 26) { gLastError = "setLayer3Params: can it nhat 26 float"; return; }
    jfloat v[32] = {};
    env->GetFloatArrayRegion(arr, 0, n < 32 ? n : 32, v);
    fdc::PostLutParams p{};
    for (int i = 0; i < fdc::kHSLBandCount; i++) {
        p.hsl[i] = {v[i * 3 + 0], v[i * 3 + 1], v[i * 3 + 2]};
    }
    p.global_saturation = v[21];
    p.clarity = 0.f;                       // clarity: pass rieng (Tuan 4)
    p.shadow_tint_rgb[0] = v[22];
    p.shadow_tint_rgb[1] = v[23];
    p.shadow_tint_rgb[2] = v[24];
    p.skin_tone_protection = v[25] > 0.5f;
    if (n >= 32) {                          // TASK S2 — Skin-Tone Lock Mask
        p.skin_mask.enabled        = v[26] > 0.5f;
        p.skin_mask.target_hue_deg = v[27];
        p.skin_mask.tolerance_deg  = v[28];
        p.skin_mask.feather_deg    = v[29];
        p.skin_mask.strength       = v[30];
        p.skin_mask.mask_view      = v[31] > 0.5f;
    }
    gRenderer->setLayer3Params(p);
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setClarity(JNIEnv*, jobject, jfloat v) {
    if (gRenderer) gRenderer->setClarity(v);
}

// TASK 5.3 — Before/After: nhan giu player = xem anh goc (chi CST)
JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setBypassGrade(JNIEnv*, jobject, jboolean on) {
    if (gRenderer) gRenderer->setBypassGrade(on == JNI_TRUE);
}

// TASK E3 — Watermark: Kotlin decode PNG (BitmapFactory) -> RGBA8 thang ->
// VkImage 2D (binding 1 cua composite). w*h*4 byte.
JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_setWatermarkImage(
        JNIEnv* env, jobject, jbyteArray rgba, jint w, jint h) {
    if (!gRenderer || w <= 0 || h <= 0) return JNI_FALSE;
    if (env->GetArrayLength(rgba) < w * h * 4) {
        gLastError = "setWatermarkImage: thieu du lieu RGBA";
        return JNI_FALSE;
    }
    std::vector<uint8_t> buf(size_t(w) * h * 4);
    env->GetByteArrayRegion(rgba, 0, w * h * 4, reinterpret_cast<jbyte*>(buf.data()));
    std::string err;
    if (!gRenderer->setWatermarkImage(buf.data(), uint32_t(w), uint32_t(h), &err)) {
        gLastError = err;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_setWatermarkEnabled(JNIEnv*, jobject, jboolean on) {
    if (gRenderer) gRenderer->setWatermarkEnabled(on == JNI_TRUE);
}

// ==================== TASK 4.1 — EXPORT (encoder surface) ====================
JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_beginExport(
        JNIEnv* env, jobject, jobject encoderSurface, jint width, jint height) {
    if (!gRenderer) { gLastError = "Renderer chua khoi tao"; return JNI_FALSE; }
    ANativeWindow* win = ANativeWindow_fromSurface(env, encoderSurface);
    if (!win) { gLastError = "Encoder surface null"; return JNI_FALSE; }
    return gRenderer->beginExport(win, uint32_t(width), uint32_t(height), &gLastError)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_submitExportFrame(
        JNIEnv* env, jobject, jobject hardwareBuffer) {
    if (!gRenderer) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) { gLastError = "HardwareBuffer null"; return JNI_FALSE; }
    if (!gRenderer->submitDecodedFrame(ahb, &gLastError)) return JNI_FALSE;
    return gRenderer->renderExportFrame(&gLastError) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_endExport(JNIEnv*, jobject) {
    if (gRenderer) gRenderer->endExport();
}

JNIEXPORT void JNICALL
Java_com_freedive_colorapp_NativeBridge_clearAhbCache(JNIEnv*, jobject) {
    if (gRenderer) gRenderer->clearAhbCache();
}

JNIEXPORT jstring JNICALL
Java_com_freedive_colorapp_NativeBridge_lastError(JNIEnv* env, jobject) {
    return toJstr(env, gLastError);
}

}  // extern "C"
