// Host stub cho <android/bitmap.h> — chi de g++ -fsyntax-only tren cloud.
// Khop chu ky NDK that (jnigraphics).
#pragma once
#include <cstdint>
#include <jni.h>

enum AndroidBitmapFormat {
    ANDROID_BITMAP_FORMAT_NONE      = 0,
    ANDROID_BITMAP_FORMAT_RGBA_8888 = 1,
    ANDROID_BITMAP_FORMAT_RGB_565   = 4,
    ANDROID_BITMAP_FORMAT_RGBA_4444 = 7,
    ANDROID_BITMAP_FORMAT_A_8       = 8,
};

enum {
    ANDROID_BITMAP_RESULT_SUCCESS           = 0,
    ANDROID_BITMAP_RESULT_BAD_PARAMETER     = -1,
    ANDROID_BITMAP_RESULT_JNI_EXCEPTION     = -2,
    ANDROID_BITMAP_RESULT_ALLOCATION_FAILED = -3,
};

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    int32_t  format;
    uint32_t flags;
} AndroidBitmapInfo;

inline int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo* info) {
    if (info) { info->width = 0; info->height = 0; info->stride = 0; info->format = 0; info->flags = 0; }
    return ANDROID_BITMAP_RESULT_SUCCESS;
}
inline int AndroidBitmap_lockPixels(JNIEnv*, jobject, void** addr) {
    if (addr) *addr = nullptr;
    return ANDROID_BITMAP_RESULT_SUCCESS;
}
inline int AndroidBitmap_unlockPixels(JNIEnv*, jobject) { return ANDROID_BITMAP_RESULT_SUCCESS; }
