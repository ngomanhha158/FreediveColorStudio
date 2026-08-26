# BÁO CÁO TUẦN 3 — POST-LUT HSL & XỬ LÝ HÀNG LOẠT + KEYFRAMING
*FreediveColorStudio · thực hiện 26/08/2026 (kích hoạt theo yêu cầu) · Task 3.1 → 3.5 hoàn thành 5/5*

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| 3.1 | **`post_lut.frag`** — Layer 3 HSL 7 kênh thành pass Vulkan riêng (midB→midC): band-weight gaussian, Skin Tone Protection, Global Saturation, Shadow Tint; tham số qua **UBO 144B std140** map thường trực; renderer thêm target midC + pass 3; **scope chuyển sang soi midC (ảnh CUỐI)** — Vectorscope canh Skin Tone Line đúng sau mọi lớp | SPIR-V OK; g++ 0 lỗi |
| 3.2 | **`ClipGalleryView.kt`** — gallery/timeline mini cuộn ngang, thumbnail nạp ngầm (thread riêng), chạm chọn clip, nhấn giữ đánh dấu multi-select (viền vàng) | Rà tay |
| 3.3 | **`GradeState.kt` + `GradeManager.kt`** — Copy Attributes: serialize đủ 3 layer + Anti-Green + eye-toggle ra `grade_clipboard.json` (schema v1) | Rà tay |
| 3.4 | **Paste to All** — áp clipboard cho các clip đã chọn (không chọn = tất cả), mỗi clip nhận bản sao độc lập; clip đang phát đẩy ngay xuống GPU | Rà tay |
| 3.5 | Build test + báo cáo + README checklist tuần 3 | — |
| CF#3 | **HSL Isolation UI** — nhóm "Skin · Orange/Red" (hue/sat/luma + khóa Skin Protection) và "Deep Sea · Cyan/Blue" tách riêng trong panel | Rà tay |
| CF#4 | **Depth-based Keyframing** — `KeyframeController.kt`: đặt KF đầu/cuối clip từ trạng thái grade hiện tại, decoder báo `presentationTimeUs` mỗi frame → `GradeState.lerp` (mirror `fdc::Evaluate`) → áp renderer; nút KF ▶/■ | Rà tay |
| Tồn đọng T2 | **Slider đồng bộ 2 chiều với preset** — `PresetLoader.kt` đọc `assets/freediving_color_presets.json` (nguồn sự thật, không hard-code), bấm preset là slider nhảy đúng giá trị + Anti-Green khởi điểm đề xuất | Rà tay |

## II. QUYẾT ĐỊNH KIẾN TRÚC TUẦN 3

### QĐ7 — Layer 3 nhận tham số bằng gì?
| | (A) Push constants | **(B) UBO 144B map thường trực — ĐÃ CHỌN** | (C) Specialization constants |
|---|---|---|---|
| Ưu | Nhanh nhất | **Đủ chỗ cho 7 band × 3 + phụ trợ (push constants chỉ đảm bảo 128B); keyframing ghi thẳng vào vùng map mỗi frame, không rebuild gì** | Bake theo preset |
| Khuyết | 7 band HSL vượt trần 128B đảm bảo | Thêm 1 UBO nhỏ (không đáng kể) | Đổi tham số phải tạo lại pipeline — chết với slider realtime |

### QĐ8 — Layer 3 đặt ở đâu trong pipeline?
| | (A) Gộp vào composite (blit) | **(B) Pass riêng midB→midC, composite blit midC — ĐÃ CHỌN** |
|---|---|---|
| Ưu | Ít hơn 1 pass fp16 | **Scope soi được ẢNH CUỐI (sau HSL) — bắt buộc để Skin Tone Line có nghĩa khi Layer 3 xoay hue; eye-toggle L3 sạch; Clarity Tuần 4 chèn tiếp sau midC tự nhiên** |
| Khuyết | Scope chỉ thấy ảnh trước HSL — sai mục đích spec | +1 lần ghi/đọc fp16 (~chấp nhận được, đo lại ở profiling Tuần 5) |

### QĐ9 — Keyframing nội suy ở đâu?
| | (A) GPU (shader đọc 2 keyframe + t) | **(B) CPU Kotlin lerp → setter JNI mỗi frame — ĐÃ CHỌN** | (C) C++ fdc::Evaluate qua JNI |
|---|---|---|---|
| Ưu | Không tốn CPU | **Đơn giản, tái dùng đường setter sẵn có, ~200 float lerp/frame là không đáng kể; GradeState.lerp mirror đúng fdc::Evaluate** | Cùng logic C++ |
| Khuyết | Nhân đôi tham số trong shader, phức tạp | Chạy trên decode thread (setter chỉ ghi struct — an toàn) | Thêm JNI marshalling qua lại, không lợi hơn (B) |

## III. TỒN ĐỌNG CHUYỂN TIẾP TUẦN 4
1. Xuất file MediaMuxer H.265 Main10 + batch render hàng đợi trên map GradeState của GradeManager (nền tảng đã sẵn).
2. Cache import AHardwareBuffer theo địa chỉ buffer; giải phóng VRAM sau từng clip trong queue.
3. Clarity pass (unsharp luma) chèn sau midC. Gamma shift check preview vs file xuất.
4. Keyframe hiện chỉ 2 mốc đầu/cuối (đúng spec); nhiều mốc hơn là mở rộng tự nhiên của `Evaluate` nếu cần.

## IV. KIỂM ĐỊNH ĐÃ CHẠY
post_lut.frag → SPIR-V PASS (tổng 7 shader đều xanh); vulkan_renderer.cpp + native_bridge.cpp g++ -fsyntax-only với Vulkan headers thật: 0 lỗi; unit test lut_parser 5/5 PASS (hồi quy); Kotlin (7 file) rà tay — không compiler trong môi trường.

## V. CHECKLIST TEST TRÊN MÁY (tuần 3)
1. Import 2-3 clip → gallery hiện thumbnail; chạm chuyển clip — grade mỗi clip độc lập.
2. Bấm preset **PQ** → slider nhảy đúng giá trị Phú Quốc (Temp +0.60, Tint +0.50, Red 0.90...).
3. Chỉnh "Sat nước" -0.3 → màu nước dịu xuống, tông da không đổi khi bật 🔒 Skin Protection.
4. Copy trên clip A → nhấn giữ chọn clip B, C → Paste All → mở B/C thấy grade giống A.
5. Đặt Red Recovery 0.8 → KF đầu; kéo về 0.1 → KF cuối; bật KF ▶ → phát clip thấy màu chuyển mượt từ đáy lên mặt nước; Vectorscope luôn phản ánh ảnh cuối.
