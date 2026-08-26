#pragma once
#include <jni.h>
struct AAssetManager;
extern "C" AAssetManager* AAssetManager_fromJava(JNIEnv*, jobject);
