#pragma once
struct ANativeWindow;
extern "C" {
int32_t ANativeWindow_getWidth(ANativeWindow*);
int32_t ANativeWindow_getHeight(ANativeWindow*);
}
