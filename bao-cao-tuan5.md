# BÁO CÁO TUẦN 5 (CUỐI) — POLISH UI & CHỐT V1.0
*FreediveColorStudio · thực hiện 26/08/2026 (kích hoạt theo yêu cầu) · Task 5.1 → 5.5 hoàn thành 5/5 · **V1.0***

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| 5.1 | **Material 3 dark theme** — `themes.xml` + `colors.xml` bảng màu "Ocean" (primary xanh nước #3987E5, secondary lagoon, nền sâu #0A1220), Manifest chuyển `Theme.FreediveColor`, dependency `material:1.12.0`; haptic đã có sẵn từ Tuần 2 trên toàn bộ slider | Rà tay |
| 5.2 | **Autosave Drafts** — `DraftStore.kt`: lưu danh sách clip + GradeState từng clip vào `draft.json`, ghi **debounce 800ms** sau mỗi thay đổi + ghi ngay ở `onPause`; mở app (kể cả sau khi bị kill) tự khôi phục nguyên trạng gallery + grade | Rà tay |
| 5.3 | **Before/After nhấn giữ** — nhấn giữ player ≥200ms → `setBypassGrade(true)`: renderer tắt trọn 3 layer + clarity (chỉ còn CST xem ảnh gốc), thả tay trở về ảnh đã grade; hoạt động cả trong preview lẫn khi so màu với scope | g++ 0 lỗi |
| 5.4 | **Final profiling** — `PROFILING.md`: đo FPS/nhiệt bằng Perfetto + `dumpsys`, danh sách điểm nóng đã biết và ngưỡng chấp nhận; các tối ưu rõ ràng đã làm từ Tuần 4 (cache AHB, scope gated, clarity early-out) | — |
| 5.5 | **V1.0** — versionName `1.0` (versionCode 5), tổng kết toàn dự án `tong-ket-du-an.md` | — |

## II. QUYẾT ĐỊNH KIẾN TRÚC TUẦN 5

### QĐ12 — Material 3 bằng cách nào?
| | **(A) MDC theme phủ lên View hiện có — ĐÃ CHỌN** | (B) Viết lại toàn bộ bằng Compose | (C) Giữ theme mặc định |
|---|---|---|---|
| Ưu | **Toàn bộ Button/SeekBar/Toggle tự nhận diện mạo M3 qua theme; 0 rủi ro hồi quy logic đã kiểm định 4 tuần; đúng "Lean & Clean"** | Chuẩn hiện đại nhất, animation đẹp | Không tốn công |
| Khuyết | Chưa có động tác chuyển cảnh M3 expressive | Viết lại ~8 file UI ở tuần cuối = rủi ro cao, trái nguyên tắc RC | Nhìn thô, sai spec 5.1 |

### QĐ13 — Autosave lưu gì, khi nào?
Chọn **JSON toàn phần + debounce**: tái dùng `GradeState.toJson` (đã là định dạng Copy Attributes), một file `draft.json` duy nhất, debounce 800ms để kéo slider không nghiến I/O, `onPause` ghi đồng bộ chốt chặn cuối. Phương án SQLite/DataStore bị loại — quá tay cho một map nhỏ.

## III. TRẠNG THÁI CUỐI V1.0 — TOÀN BỘ TÍNH NĂNG
Pipeline Vulkan 4 pass (CST/Layer1 → LUT tetrahedral → HSL 7 kênh → composite+clarity) · 5 preset từ spec + Anti-Green + eye-toggle 3 layer · Floating Scopes (Vectorscope/Waveform, kéo-thả snap, pinch, smart opacity) · Gallery đa clip + Copy/Paste Attributes + Batch export · Depth-based Keyframing · Xuất HEVC Main10 BT.709 + notification · Cache AHB + trả VRAM từng clip · Autosave draft · Before/After nhấn giữ · Material 3 dark.

## IV. VIỆC CÒN MỞ SAU V1.0 (không chặn phát hành nội bộ)
1. **Hằng số D-Log M chính thức của DJI** — hàm CST vẫn là xấp xỉ có đánh dấu; đây là việc giá trị nhất tiếp theo.
2. PTS export chính xác tuyệt đối (VK_GOOGLE_display_timing) nếu đo thấy lệch trên Pixel thật.
3. Nút "Lưu vào Thư viện" (MediaStore) cho file xuất; màn Settings; nhiều keyframe hơn 2 mốc.
4. Video promo (đã hoãn theo yêu cầu — nên quay màn hình app thật bây giờ khi đã có V1.0).
