#pragma once
#include <jni.h>
struct ANativeWindow;
extern "C" ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject);
