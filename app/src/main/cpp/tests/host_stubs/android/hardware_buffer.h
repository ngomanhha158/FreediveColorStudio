#pragma once
#include <cstdint>
struct AHardwareBuffer;
typedef struct AHardwareBuffer_Desc {
    uint32_t width, height, layers, format;
    uint64_t usage; uint32_t stride, rfu0; uint64_t rfu1;
} AHardwareBuffer_Desc;
extern "C" void AHardwareBuffer_describe(const AHardwareBuffer*, AHardwareBuffer_Desc*);
extern "C" void AHardwareBuffer_acquire(AHardwareBuffer*);
extern "C" void AHardwareBuffer_release(AHardwareBuffer*);
