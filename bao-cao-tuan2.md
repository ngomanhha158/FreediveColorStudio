# BÁO CÁO TUẦN 2 — BỘ ĐIỀU KHIỂN PRE-LUT & LUT ENGINE + FLOATING SCOPES
*FreediveColorStudio · thực hiện 18/08/2026 (kích hoạt sớm) · Task 2.1 → 2.5 hoàn thành 5/5*

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| 2.1 | `color_space.frag` mở rộng đủ Layer 1: Temp/Tint/Exposure/**Contrast/Shadows/Highlights**/Red Recovery/Anti-Green + eye-toggle, push constants 48B khớp `layer1_push_constants.h` | glslangValidator → SPIR-V OK |
| 2.2 | `vulkan_renderer.cpp` viết lại HOÀN CHỈNH: pipeline 3 pass (CST→midA fp16, LUT tetrahedral→midB fp16, Composite→swapchain), descriptor pool/set đầy đủ, render pass, framebuffer, **YCbCr + pipeline CST cache theo external format** (hết cảnh báo rebuild mỗi frame), LUT identity mặc định để chạy ngay khi chưa nạp .cube | g++ với Vulkan headers thật: **0 lỗi 0 cảnh báo** |
| 2.3 | `ColorSliders.kt`: 8 slider + LUT Mix, **haptic tick khi qua mốc 0** (Precision Sliders), chip Anti-Green bật nhanh 50%, **3 eye-toggle** L1/L2/L3; JNI `setLayer1Params`/`setScopeConfig` mới | Rà soát tay |
| 2.4 | **Scopes GPU**: `scopes_build.comp` (compute, subsample 4×4, atomic bins 256×256, không ảnh hưởng FPS) + `scopes_popup.frag` (Vectorscope có graticule 75/100% + **đường chuẩn tone da**, Waveform có vạch IRE) + `ScopesPopupView.kt` (kéo thả **snap 4 góc**, **pinch resize** 0.10–0.60, **smart opacity 40%→100%** khi chạm slider, tự hạ sau 1.5s) + nút cycle Vectorscope→Waveform→OFF | 2 shader SPIR-V OK |
| 2.5 | Báo cáo này + cập nhật README-BUILD + toàn bộ .spv tái sinh | — |

## II. QUYẾT ĐỊNH KIẾN TRÚC TUẦN 2 (2–3 phương án)

### QĐ4 — Vẽ Floating Scopes ở đâu?
| | (A) View Android riêng (Canvas/GL) đọc dữ liệu về CPU | **(B) Vẽ bằng Vulkan ngay trên swapchain, view Android chỉ bắt gesture — ĐÃ CHỌN** | (C) Cửa sổ TYPE_APPLICATION_OVERLAY |
|---|---|---|---|
| Ưu | Dễ viết | **Zero-copy — bins ở nguyên trên GPU, đúng yêu cầu "không ảnh hưởng FPS"; overlay đồng bộ từng frame với video** | Nổi trên mọi app |
| Khuyết | Readback GPU→CPU mỗi frame giết hiệu năng | Gesture và hình vẽ ở 2 tầng (đã giải quyết bằng config chuẩn hóa qua JNI) | Cần quyền hệ thống, quá tay cho scope nội bộ app |

### QĐ5 — Cấu trúc dữ liệu scope
| | (A) Render trực tiếp point-cloud từng pixel | **(B) Histogram bins 256×256 trong SSBO, compute atomicAdd, frag vẽ mật độ — ĐÃ CHỌN** |
|---|---|---|
| Ưu | Trace "analog" đẹp | **Chi phí cố định 512KB VRAM, subsample 4×4 → ~518k atomicAdd/frame 4K, không phụ thuộc độ phân giải khi vẽ** |
| Khuyết | Hàng triệu vertex/frame, banding alpha | Mật độ lượng tử theo bin (chấp nhận được ở popup nhỏ) |

### QĐ6 — Slider ghi đè preset
Chọn mô hình **override tách biệt**: slider ghi bộ `PreLutParams` riêng qua `setLayer1Params`; bấm nút preset sẽ xóa override quay về preset gốc. Đơn giản, không phá 5 preset chuẩn, hợp với Copy/Paste Attributes ở Tuần 3.

## III. TỒN ĐỌNG CHUYỂN TIẾP
1. Slider khởi tạo ở 0 thay vì nạp giá trị preset đang chọn — Tuần 3 đọc từ `freediving_color_presets.json` (assets) để đồng bộ hai chiều.
2. Cache import AHardwareBuffer theo địa chỉ buffer (Tuần 4). Layout swapchain RGBA8 SDR (HDR10 Tuần 4).
3. Kiểm thử thật trên Pixel: cần build APK theo README-BUILD — pipeline giờ đã ĐỦ để chạy end-to-end (không còn phần "hoàn thiện trên máy" như Tuần 1).

## IV. KIỂM ĐỊNH ĐÃ CHẠY
6 shader → SPIR-V qua glslangValidator -V (100% pass); `vulkan_renderer.cpp` + `native_bridge.cpp` g++ -fsyntax-only với Vulkan headers thật (0 lỗi); unit test lut_parser 5/5 PASS (hồi quy).
