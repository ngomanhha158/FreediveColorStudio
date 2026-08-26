# FreediveColorStudio — core engine (NDK)
cmake_minimum_required(VERSION 3.22)
project(freedivecolor_core CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(colorcore SHARED
    lut_parser.cpp
    lut_preview.cpp   # Task E4 — LUT thumbnail live (CPU trilinear + AndroidBitmap)
    vulkan_renderer.cpp
    native_bridge.cpp
)
target_include_directories(colorcore PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(colorcore
    vulkan
    android
    log
    nativewindow      # AHardwareBuffer / ANativeWindow
    mediandk          # (Tuan 4: MediaMuxer NDK)
    jnigraphics       # Task E4 — AndroidBitmap_lockPixels cho LUT thumbnail
)
