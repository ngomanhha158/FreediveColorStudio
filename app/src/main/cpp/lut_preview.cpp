// ============================================================================
//  TASK E4 (spec "Fable Week 4 bo sung" 4.4) — LIVE LUT THUMBNAIL (C++ bridge)
//  Kotlin dua Bitmap RGBA_8888 (frame thu nho ~256px tu clip dang mo) + duong
//  dan .cube; C++ ap LUT TAI CHO (in-place) bang noi suy TRILINEAR tren CPU
//  roi tra ve — moi LUT trong thu vien co thumbnail "live" dung frame that.
//
//  Vi sao CPU (QĐ17): thumbnail 256x144 = ~37k pixel; trilinear CPU < 2ms/anh
//  tren Tensor — re hon nhieu so voi vong render-to-texture + readback Vulkan
//  (phai dung preview, stash swapchain nhu export). Parser .cube tai su dung
//  fdc::LoadCubeLutFromFile (Task 1.4, unit test 5/5).
// ============================================================================
#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include "lut_parser.h"

namespace {

/** Noi suy trilinear tren luoi N^3 RGBA float (RED chay nhanh nhat — chuan Adobe). */
inline void sampleTrilinear(const fdc::CubeLut& lut, float r, float g, float b,
                            float* outR, float* outG, float* outB) {
    const int N = lut.size;
    const float scale = float(N - 1);
    float fr = r * scale, fg = g * scale, fb = b * scale;
    int r0 = int(fr), g0 = int(fg), b0 = int(fb);
    if (r0 > N - 2) r0 = N - 2;
    if (g0 > N - 2) g0 = N - 2;
    if (b0 > N - 2) b0 = N - 2;
    const float tr = fr - r0, tg = fg - g0, tb = fb - b0;

    const float* d = lut.rgba.data();
    auto at = [&](int ri, int gi, int bi) {
        return d + (size_t(ri) + size_t(gi) * N + size_t(bi) * N * N) * 4;
    };
    float acc[3] = {0.f, 0.f, 0.f};
    for (int dz = 0; dz <= 1; dz++)
        for (int dy = 0; dy <= 1; dy++)
            for (int dx = 0; dx <= 1; dx++) {
                const float w = (dx ? tr : 1.f - tr) * (dy ? tg : 1.f - tg) * (dz ? tb : 1.f - tb);
                const float* p = at(r0 + dx, g0 + dy, b0 + dz);
                acc[0] += w * p[0];
                acc[1] += w * p[1];
                acc[2] += w * p[2];
            }
    *outR = acc[0]; *outG = acc[1]; *outB = acc[2];
}

inline uint8_t clamp255(float v) {
    const int i = int(v * 255.f + 0.5f);
    return uint8_t(i < 0 ? 0 : (i > 255 ? 255 : i));
}

}  // namespace

extern "C" {

/**
 * Ap LUT .cube len Bitmap RGBA_8888 TAI CHO. Tra ve false neu bitmap sai dinh
 * dang hoac file .cube khong hop le (bitmap giu nguyen).
 */
JNIEXPORT jboolean JNICALL
Java_com_freedive_colorapp_NativeBridge_applyLutToBitmap(
        JNIEnv* env, jobject, jobject bitmap, jstring cubePath) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(cubePath, nullptr);
    std::string errStr;
    fdc::CubeLut lut = fdc::LoadCubeLutFromFile(path ? path : "", &errStr);
    env->ReleaseStringUTFChars(cubePath, path);
    if (!lut.valid()) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;

    const float k = 1.f / 255.f;
    for (uint32_t y = 0; y < info.height; y++) {
        uint8_t* row = reinterpret_cast<uint8_t*>(pixels) + size_t(y) * info.stride;
        for (uint32_t x = 0; x < info.width; x++) {
            uint8_t* px = row + size_t(x) * 4;
            float r, g, b;
            sampleTrilinear(lut, px[0] * k, px[1] * k, px[2] * k, &r, &g, &b);
            px[0] = clamp255(r);
            px[1] = clamp255(g);
            px[2] = clamp255(b);
            // px[3] (alpha) giu nguyen
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

}  // extern "C"
