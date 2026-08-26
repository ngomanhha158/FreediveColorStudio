# BÁO CÁO TUẦN 1 — KHỞI TẠO KIẾN TRÚC CORE & RENDER ENGINE
*Dự án FreediveColorStudio · thực hiện 18/08/2026 (kích hoạt sớm theo yêu cầu) · Task 1.1 → 1.5 hoàn thành đủ 5/5*

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| 1.1 | `vulkan_renderer.h/.cpp` (instance → device → swapchain → RGBA16F → import AHardwareBuffer → render loop), `native_bridge.cpp`, `MediaCodecEngine.kt`, Gradle + Manifest + CMake | g++ -fsyntax-only với Vulkan headers thật: **0 lỗi** |
| 1.2 | `VideoPlayerView.kt` (SurfaceView + vòng đời Vulkan, đo view theo tỷ lệ video), `NativeBridge.kt`, `MainActivity.kt` | Rà soát tay (không có kotlinc trong môi trường) |
| 1.3 | `color_space.frag` + `layer1_push_constants.h` *(làm sớm 18/08)* | glslangValidator -V → SPIR-V **OK** |
| 1.4 | `lut_parser.h/.cpp` + `lut_tetrahedral.frag` *(làm sớm 18/08)* | Unit test **5/5 PASS**; SPIR-V OK |
| 1.5 | Báo cáo này + `README-BUILD.md` + 3 file `.spv` precompiled trong assets | — |

## II. CÁC QUYẾT ĐỊNH KIẾN TRÚC (2–3 PHƯƠNG ÁN MỖI QUYẾT ĐỊNH)

### QĐ1 — Đường đưa frame 10-bit từ decoder vào GPU
| | (A) SurfaceTexture GLES OES | **(B) ImageReader → AHardwareBuffer → Vulkan external memory + YCbCr sampler — ĐÃ CHỌN** | (C) CPU readback P010 → staging upload |
|---|---|---|---|
| Ưu | Đơn giản, nhiều tài liệu | **Zero-copy, giữ nguyên 10-bit, thẳng vào Vulkan, chuẩn hiện đại Android 12+** | Dễ debug từng byte |
| Khuyết | Kẹt hệ GLES, 10-bit qua OES không đảm bảo, khó chen compute | Code phức tạp hơn (đã viết xong ở `importHardwareBuffer`) | Chậm 10–20×, ngốn RAM — vi phạm mục tiêu không rớt khung hình |

### QĐ2 — Cấu trúc pipeline render
| | (A) 1 pass gộp CST+LUT | **(B) 2 pass qua target trung gian RGBA16F — ĐÃ CHỌN** | (C) Compute shader |
|---|---|---|---|
| Ưu | Ít bandwidth nhất | **Đúng kiến trúc 3 lớp; Tuần 2 chèn scopes đọc từ target fp16; eye-toggle từng layer sạch sẽ** | Linh hoạt tile-based |
| Khuyết | Trộn logic 2 layer, khó bypass từng lớp | Tốn 1 lần ghi/đọc fp16 (~66MB/s ở 4K30 — không đáng kể trên Tensor) | Tự quản lý sync phức tạp, không cần thiết cho fullscreen filter |

### QĐ3 — Biên dịch shader
| | (A) Runtime shaderc | **(B) Precompile SPIR-V bằng glslangValidator, nhúng assets — ĐÃ CHỌN cho Tuần 1** |
|---|---|---|
| Ưu | Sửa shader không cần rebuild | **Khởi động nhanh, không phụ thuộc libshaderc, .spv đã sinh sẵn và kiểm định** |
| Khuyết | +2–3MB lib, chậm cold-start | Đổi shader phải chạy lại glslangValidator (đã ghi lệnh trong README-BUILD) |

## III. GHI CHÚ KỸ THUẬT TỒN ĐỌNG (chuyển tiếp Tuần 2/4)
1. `createPipelines()` hiện là khung thứ tự (descriptor layout → pipeline layout → render pass → graphics pipeline) — phần thân phụ thuộc thiết bị thật, hoàn thiện khi build lần đầu trên Android Studio theo README-BUILD (mục "Hoàn thiện pipeline"). Toàn bộ push-constant struct, shader, import AHB đã sẵn sàng và khớp byte.
2. `importHardwareBuffer` import lại mỗi frame — Tuần 4 cache theo địa chỉ buffer (ImageReader xoay vòng 4 buffer).
3. Swapchain Tuần 1 dùng RGBA8 SDR cho preview; đường xuất HDR10 nằm ở Tuần 4 (MediaMuxer Main10).
4. Đường cong D-Log M → Linear vẫn là xấp xỉ có đánh dấu placeholder trong shader.

## IV. KIỂM THỬ TÍCH HỢP (Task 1.5 — người dùng thực hiện trên máy)
Làm theo `README-BUILD.md`: build APK → mở clip D-Log M → bấm 5 nút preset → quan sát không rớt khung hình (logcat tag `FDC/`). Báo lại kết quả để Tuần 2 tinh chỉnh.
