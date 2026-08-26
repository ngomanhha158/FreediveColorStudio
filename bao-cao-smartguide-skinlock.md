# BÁO CÁO BỔ SUNG V1.1-dev — SMART GUIDE & SKIN-TONE LOCK MASK
*FreediveColorStudio · thực hiện 26/08/2026 theo spec bổ sung "Prompts for Fable Week 3" (Smart Guide + Skin-Tone Lock) · versionName 1.1-dev (versionCode 6)*

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| S1 | **SmartGuideManager.kt** — máy trạng thái quy trình grade 5 bước trên `StateFlow` (spec yêu cầu StateFlow/LiveData): ① WB/Tint/Anti-Green → ② bù Exposure+Shadows *chỉ khi Tint magenta > 20%* (điều kiện động đúng spec) → ③ Red Recovery → ④ LUT Engine → ⑤ HSL Cyan/Blue. Trạng thái là **hàm thuần túy** của GradeState + tập bước bỏ qua — người dùng kéo về 0 thì guide tự lùi bước, không cần reset; có `skipCurrent()`, `reset()`, `setProMode()` | Rà tay |
| S1 | **Tích hợp ColorSliders.kt** — mỗi slider nằm trong container viền bo tròn; slider được gợi ý **đập sáng theo nhịp** (một `ValueAnimator` 700ms REVERSE dùng chung, cập nhật stroke + màu tiêu đề, hủy ở `onDetachedFromWindow`); công tắc **Pro Guide** = `MaterialSwitch` (M3) + dòng gợi ý bước hiện tại; tắt là mọi highlight dừng (spec mục 3) | Rà tay |
| S1 | **docs/compose-ref/SmartGuideHighlight.kt** — bản Jetpack Compose đầy đủ theo nguyên văn spec (`rememberInfiniteTransition` + border/shadow pulse + `Switch` + `collectAsState`), đặt NGOÀI sourceSet để không phá build View hiện tại (xem QĐ14) | Rà tay |
| S2 | **post_lut.frag** — Skin-Tone Lock Mask chạy 100% GPU trong pass Layer 3: RGB→HSV từ **màu đầu vào pass**, khoảng cách hue tròn tới hue da mục tiêu, lõi bảo vệ trọn vẹn ≤ tolerance, **feather `smoothstep(tol, tol+feather, d)`** không ria cứng (spec mục 2), gate saturation + value loại hạt trắng/wetsuit đen; áp `final = mix(adjusted, input, mask·strength)` — chỉnh Layer 3 chỉ tác động **nghịch đảo mask** (spec mục 3); chế độ **xem mask** grayscale | glslangValidator -V PASS |
| S2 | **C++ interface** (spec mục 4): `SkinMaskParams` trong color_pipeline.h (target_hue/tolerance/feather/strength/enabled/mask_view/gates, mặc định đo cho da dưới nước nhiệt đới); `Layer3Ubo` 144B→**176B** (+2 vec4, static_assert); fill UBO trong renderer (bypass Before/After tắt cả mask lẫn mask view); JNI `setLayer3Params` 26→**32 float** nhận mảng cũ 26 vẫn hợp lệ | g++ -fsyntax-only 0 lỗi 0 cảnh báo |
| S2 | **GradeState.kt** — 5 trường skinLock (on/hue/tol/feather/strength) vào JSON (Copy/Paste + autosave draft, draft cũ vẫn đọc được) + lerp keyframe (bool giữ keyframe đầu, số nội suy); **UI**: nhóm "Skin Lock Mask" trong ColorSliders (chip bật + chip Xem mask + 4 slider Hue/Tolerance/Feather/Strength); mask view là debug view, KHÔNG thuộc GradeState, reset khi đổi clip | Rà tay |

Kiểm định chung: `post_lut.frag` compile SPIR-V PASS · `vulkan_renderer.cpp` + `native_bridge.cpp` g++ -fsyntax-only (Vulkan headers thật + JNI headers + Android stubs) 0 lỗi 0 cảnh báo · unit test LUT parser 5/5 PASS (không hồi quy) · Kotlin rà tay (môi trường không có kotlinc).

## II. QUYẾT ĐỊNH KIẾN TRÚC

### QĐ14 — Smart Guide UI: spec ghi Jetpack Compose, app đang là classic View (QĐ12)
| | **(A) Logic StateFlow chung + highlight View-based, kèm bản Compose tham chiếu — ĐÃ CHỌN** | (B) Compose interop (`ComposeView` chèn vào panel View) | (C) Viết lại panel bằng Compose |
|---|---|---|---|
| Ưu | **Chạy ngay trên app đã kiểm định 5 tuần; 0 dependency UI mới; SmartGuideManager thuần StateFlow nên phần lõi VẪN đúng nguyên văn spec; bản Compose giao đủ ở docs/compose-ref/ dùng được khi migrate** | Dùng đúng API Compose spec nêu | Chuẩn spec tuyệt đối |
| Khuyết | Hiệu ứng viết bằng ValueAnimator thay vì `rememberInfiniteTransition` | +3 dependency Compose (~2MB) chỉ cho một hiệu ứng viền; hai hệ đo lường xen kẽ trong một ScrollView dễ lỗi focus/scroll | Trái QĐ12, đập lại 8 file UI đã kiểm định — rủi ro cao nhất |
| Ghi chú | Đổi sang (B)/(C) sau này chỉ cần thay lớp hiển thị — logic không đổi | | |

### QĐ15 — Skin-Tone Lock Mask đặt ở đâu trong pipeline?
| | **(A) Tính mask ngay trong post_lut.frag, không thêm pass — ĐÃ CHỌN** | (B) Pass compute riêng ghi mask R8 + sample ở Layer 3 | (C) Phân vùng da bằng Tensor NPU (ML) |
|---|---|---|---|
| Ưu | **0 pass mới, 0 texture mới, 0 băng thông thêm — mask dùng đúng tại chỗ cần; mask tính từ màu đầu vào pass nên bảo vệ chính xác "khỏi chỉnh Layer 3" như spec; bypass/eye-toggle thừa hưởng logic sẵn có** | Mask tái dùng được cho pass khác (vd. clarity chừa da); dễ soi riêng | Chính xác nhất với da đa dạng, không phụ thuộc hue |
| Khuyết | Mask chỉ tồn tại trong pass Layer 3 (đủ với yêu cầu hiện tại) | +1 pass +1 image +barrier, tốn ~0.3ms 4K vô ích khi chỉ Layer 3 dùng | Ngoài phạm vi spec (spec yêu cầu shader hue-based); độ trễ NPU/frame + phức tạp lớn; để dành làm nâng cấp V2 |
| Ghi chú | Chế độ "Xem mask" thay cho khả năng debug của (B) | | |

**Tương tác với Skin Tone Protection (Tuần 3):** hai cơ chế độc lập, dùng được đồng thời — Protection *giới hạn* chỉnh HSL trong dải 15–45° ngay khi tính band; Lock Mask *khôi phục* pixel da về màu đầu vào pass sau toàn bộ chỉnh Layer 3 (kể cả Global Sat + Shadow Tint) theo mask mềm có gate. Lock Mask mạnh và chính xác hơn cho ca "kéo Cyan/Blue rất sâu".

## III. HƯỚNG DẪN TEST TRÊN PIXEL (bổ sung checklist V1.0)
1. **Smart Guide:** mở clip mới (grade 0) → hàng Temp/Tint/Anti-Green phải đập sáng + dòng gợi ý ①; kéo Tint lên +0.25 → gợi ý chuyển sang ② EV/Shadows; đưa Tint về 0.15 → quay lại nhóm ③ (Red Recovery) nếu chưa chỉnh; tắt Pro Guide → mọi viền về trạng thái thường ngay lập tức.
2. **Skin Lock:** áp preset Deep Sea Blue, kéo "Hue nước" −30 → da thợ lặn ám xanh; bật 🎭 Skin Lock → da trở lại, nền vẫn xanh; bật "Xem mask" → vùng da trắng, nước đen, biên mềm (không răng cưa); tăng Feather → biên loang rộng hơn; Before/After nhấn giữ vẫn ra ảnh gốc (mask không can thiệp).
3. **Round-trip:** Copy Attributes clip có Skin Lock → Paste sang clip khác → chip + 4 slider khớp; kill app mở lại → draft khôi phục đủ trường skinLock.

## IV. VIỆC CÒN MỞ
1. Hue mục tiêu tự động theo độ sâu (liên kết Depth Keyframing — da lệch đỏ dần khi xuống sâu): lerp `skinLockHue` đã hoạt động qua keyframe, còn thiếu auto-detect.
2. Nâng cấp V2 (tùy chọn): mask ML trên Tensor NPU (phương án C của QĐ15) cho da đa sắc tộc/ngược sáng.
3. Smart Guide bước ④ hiện coi "đã đụng LUT" khi đổi LUT Mix hoặc nạp .cube — có thể thêm nút "Bỏ qua bước" trên panel (API `skipCurrent()` đã có, chưa gắn nút).
