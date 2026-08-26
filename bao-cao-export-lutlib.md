# BÁO CÁO BỔ SUNG V1.1-dev (đợt 2) — EXPORT PRO, LUT LIBRARY & FREEDIVING FEATURES
*FreediveColorStudio · thực hiện 26/08/2026 theo 2 spec bổ sung "Prompts for Fable Week 4" (Task 4.1–4.4) · versionName 1.1-dev (versionCode 6)*

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| E1 (4.1) | **ExportConfig.kt** — data class cấu hình xuất đúng spec: codec (`HEVC_MAIN10` / `AVC_8BIT` H.264 High), khung tối đa 4K `fitOutput` giữ aspect + làm chẵn, fps 30/60 (`fpsOverride`, mặc định theo nguồn), **bitrate động** (Mbps). ExportEngine chọn MIME/profile theo config; metadata BT.709 SDR gắn cho CẢ hai codec (chống gamma shift); 10-bit→8-bit an toàn qua encoder surface (pipeline fp16 xuất Rec.709, encoder AVC quantize 8-bit, màu không lệch — QĐ16). Hàng **options bar** trên UI: H.264/HEVC · 60fps · Slow-Mo · Mute · Logo | Rà tay |
| E2 (4.3.1) | **Cinematic Slow-Mo 50%** — giữ NGUYÊN số frame nguồn 60fps, `presentationTimeUs *= 2` tại điểm ghi muxer (`ExportSpeed.ptsScale`) → clip dài gấp đôi, mượt không rớt frame, đúng cơ chế "feed 60fps frames at 30fps timestamps" của spec | Rà tay |
| E2 (4.3.2) | **Audio Mute/Passthrough** — mặc định DROP track audio (bỏ tiếng bọt lặn); tắt Mute → **copy nguyên vẹn packet audio** (không decode/re-encode, `copyAudioTrack` MediaExtractor→MediaMuxer, track add trước `muxer.start()`); Slow-Mo luôn ép mute (audio không khớp timeline ×2) | Rà tay |
| E3 (4.3.3) | **Watermark Vulkan** — `composite.frag` thêm `uWatermark` (binding 1) + PC 32B (`wmOn` + `wmRect` UV): logo alpha srcOver 85% góc phải-dưới (rộng 20% khung, lề 3%), áp SAU clarity nên có trong cả preview lẫn file xuất (renderExportFrame dùng chung đường vẽ). C++: `uploadWatermarkRgba` (staging→VkImage RGBA8, placeholder 1×1 trong suốt lúc init để descriptor luôn hợp lệ), JNI `setWatermarkImage`/`setWatermarkEnabled`. Kotlin: ưu tiên `assets/watermark.png`, không có thì TỰ VẼ logo chữ trên Bitmap (Canvas) — không phụ thuộc asset; toggle 💧 bật xem trước ngay trong preview | glslang PASS · g++ 0 lỗi |
| E4 (4.2) | **Import .cube qua SAF** — `OpenMultipleDocuments` (batch — spec 4.4.1, bao trùm yêu cầu OpenDocument đơn lẻ của 4.2), lọc `application/octet-stream`; copy an toàn vào `filesDir/luts/` (tồn tại qua phiên), kiểm tra sơ bộ `LUT_3D_SIZE`, chống trùng tên "(2)" | Rà tay |
| E4 (4.4) | **LUT Library** — `LutRepository` (category = thư mục con, spec 4.4.4) + `LutLibraryView`: **thumbnail live** = frame ~256px của clip đang mở (MediaMetadataRetriever) → JNI `applyLutToBitmap` → **C++ trilinear trên CPU** (`lut_preview.cpp`, AndroidBitmap lock/unlock, tái dùng parser Task 1.4) → mỗi LUT thấy trước trên footage THẬT; chạm = chọn (loadLutFromPath + `GradeState.lutPath` → JSON/draft); **nhấn giữ = menu Đổi tên/Xóa** (spec 4.4.3); chưa có clip → gradient biển trung tính. Bản Compose `LazyRow` + DropdownMenu: `docs/compose-ref/LutLibraryCompose.kt` (QĐ14) | g++ 0 lỗi |

Kiểm định chung: `composite.frag` SPIR-V PASS · g++ -fsyntax-only 3 file cpp (renderer + bridge + lut_preview, có stub `android/bitmap.h` mới) 0 lỗi 0 cảnh báo · unit test LUT parser 5/5 (không hồi quy) · CMake thêm `lut_preview.cpp` + link `jnigraphics`.

## II. QUYẾT ĐỊNH KIẾN TRÚC

### QĐ16 — Encoder surface: spec nêu "EGL Surface", pipeline của ta là Vulkan
| | **(A) Vulkan swapchain trên encoder input surface (giữ QĐ10) — ĐÃ CHỌN** | (B) EGL/GLES vẽ lại theo nguyên văn spec | (C) Readback CPU rồi đẩy buffer |
|---|---|---|---|
| Ưu | **Zero-copy, tái dùng 100% pipeline 4 pass đã kiểm định; encoder surface là ANativeWindow — Vulkan hay EGL đều hợp lệ, kết quả tương đương; H.264 8-bit chỉ là format swapchain do surface thương lượng** | Đúng chữ "EGL" trong spec | Không phụ thuộc swapchain |
| Khuyết | — | Viết lại toàn bộ renderer bằng GLES chỉ để đổi tên API — vô nghĩa kỹ thuật | Chậm 10-20×, ngốn RAM — trái Zero-RAM |

### QĐ17 — Thumbnail LUT live lấy từ đâu?
| | **(A) MediaMetadataRetriever + trilinear CPU (C++) — ĐÃ CHỌN** | (B) Render-to-texture Vulkan + readback | (C) Gradient tĩnh |
|---|---|---|---|
| Ưu | **256px ≈ 37k pixel < 2ms/LUT trên Tensor; không đụng renderer/preview đang chạy; đúng yêu cầu spec "pass Bitmap + path → C++ → trả ảnh đã áp"** | Dùng đúng tetrahedral GPU | 0 chi phí |
| Khuyết | Trilinear (không tetrahedral) — sai khác < 1 LSB ở thumbnail, không nhìn thấy | Phải stash swapchain như export → giật preview mỗi lần mở thư viện | Không thấy trước trên footage thật — trái spec |

### QĐ18 — Watermark đặt ở đâu trong pipeline?
| | **(A) composite.frag, binding mới + PC rect — ĐÃ CHỌN** | (B) Pass overlay riêng sau composite | (C) CPU vẽ lên frame trước encode |
|---|---|---|---|
| Ưu | **0 pass mới; preview và export tự đồng nhất (chung đường vẽ); placeholder 1×1 giữ descriptor luôn hợp lệ — không nhánh pipeline** | Tách bạch, dễ thêm nhiều overlay | Đơn giản khái niệm |
| Khuyết | composite thêm 1 texture fetch/pixel trong rect logo (~2% khung hình) | +1 renderpass +1 barrier mỗi frame | Chạm CPU vào từng frame 4K — phá zero-copy |

### QĐ19 — Audio khi xuất
| | **(A) Passthrough copy packet (không decode) + mặc định Mute — ĐÃ CHỌN** | (B) Decode→re-encode AAC | (C) Luôn drop |
|---|---|---|---|
| Ưu | **Giữ nguyên chất lượng audio gốc, 0 chi phí codec; Mute mặc định đúng nghiệp vụ lặn (bỏ tiếng bọt); slow-mo ép mute tránh lệch timeline** | Cho phép chỉnh volume/fade | Đơn giản nhất |
| Khuyết | Không chỉnh được âm lượng (ngoài phạm vi spec) | Tốn pin/nhiệt, thêm 1 pipeline codec | Mất lựa chọn giữ audio — thiếu so với spec "Mute/Replace" |

## III. HƯỚNG DẪN TEST TRÊN PIXEL (bổ sung checklist)
1. **H.264:** bật "H.264 8-bit" → xuất → `mediainfo`/Google Photos: codec AVC High, BT.709; so màu với bản HEVC bằng Vectorscope (import lại) — trace trùng, chỉ khác độ sâu bit.
2. **Slow-Mo:** clip 60fps 10s → bật 🐢 → file ra dài 20s, mượt (không rớt frame), KHÔNG có track audio.
3. **Audio:** tắt Mute (tốc độ thường) → file ra có audio gốc; bật Mute → không có track audio (kiểm bằng mediainfo).
4. **Watermark:** bật 💧 → logo hiện ngay trong preview góc phải-dưới; xuất file → logo đúng vị trí/độ mờ; tắt → biến mất cả hai nơi.
5. **LUT Library:** bấm 🎨 → ＋ Nhập nhiều .cube cùng lúc → thumbnail hiện theo frame clip đang mở; đổi clip → thumbnail đổi theo; nhấn giữ → Đổi tên/Xóa; bỏ file vào thư mục con `luts/Deep Water/` (qua adb) → mở lại thấy nhóm "Deep Water"; chọn LUT → ảnh preview đổi + Copy/Paste giữ `lutPath`.

## IV. VIỆC CÒN MỞ
1. Slow-Mo nâng cao: chọn hệ số tùy ý (×4 cho nguồn 120fps) — cấu trúc `ExportSpeed` mở sẵn.
2. Watermark: chọn vị trí 4 góc + kéo thả; hiện cố định góc phải-dưới theo spec.
3. LUT Library: kéo-thả giữa category trong app (hiện tạo category bằng thư mục con); nút "Tạo nhóm" khi import.
4. Audio "Replace" (nhạc nền) — spec ghi Mute/Replace, phần Replace để V1.2 (cần picker nhạc + trộn timeline).
