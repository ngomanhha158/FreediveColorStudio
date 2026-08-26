#pragma once
#include <cstddef>
struct AAssetManager; struct AAsset;
enum { AASSET_MODE_BUFFER = 3 };
extern "C" {
AAsset* AAssetManager_open(AAssetManager*, const char*, int);
long AAsset_getLength(AAsset*);
const void* AAsset_getBuffer(AAsset*);
void AAsset_close(AAsset*);
}
