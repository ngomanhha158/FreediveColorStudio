#pragma once
enum { ANDROID_LOG_ERROR = 6 };
inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
