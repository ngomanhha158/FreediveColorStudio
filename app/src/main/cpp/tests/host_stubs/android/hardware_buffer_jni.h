#pragma once
#include <jni.h>
struct AHardwareBuffer;
extern "C" AHardwareBuffer* AHardwareBuffer_fromHardwareBuffer(JNIEnv*, jobject);
