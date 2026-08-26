# FreediveColorStudio — trang thai 18/08/2026

App chinh mau native Android cho footage DJI Action D-Log M 10-bit
(Google Pixel 10 Pro Max / Tensor). Cau truc thu muc theo spec
"PROJECT INITIALIZATION".

## Da hoan thanh (kiem tra tu dong dat)
- [Task 1.3] assets/shaders/color_space.frag — CST D-Log M -> Linear/Rec.709
  + Layer 1 (WB/Tint/Exposure/Red Recovery/Anti-Green) qua push constants.
  Kem cpp/layer1_push_constants.h (struct C++ khop tung byte + snippet Vulkan).
- [Task 1.4] cpp/lut_parser.{h,cpp} — parser .cube 33^3/65^3 chuan Adobe,
  dong goi RGBA float cho VkImage 3D. UNIT TEST: 5/5 PASS (tests/).
  assets/shaders/lut_tetrahedral.frag — noi suy tu dien + LUT Mix slider.
- assets/shaders/freediving_color.frag — shader tham chieu du 3 layer
  (5 preset, Anti-Green, eye-toggle 3 layer); assets/freediving_color_presets.json.
- cpp/color_pipeline.h — structs 5 preset + LayerVisibility + GradeKeyframe/Lerp
  (nen tang cho Depth-based Keyframing, trien khai day du Tuan 3).

## Chua lam (tuan 1 chay tu dong 24/08)
- [Task 1.1] vulkan_renderer.cpp + MediaCodecEngine.kt (HW decode HEVC 10-bit)
- [Task 1.2] VideoPlayerView.kt (SurfaceTexture FP16)
- [Task 1.5] Build APK test dau tien

## Kiem tra da chay
g++ -fsyntax-only tat ca header; unit test lut_parser PASS;
compile-check 3 shader qua WebGL2/SwiftShader (chuyen doi tu dong 450->300es).
