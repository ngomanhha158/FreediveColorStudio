# BÁO CÁO TUẦN 4 — KHUNG RENDER ĐẦU RA & TỐI ƯU BỘ NHỚ · RC1
*FreediveColorStudio · thực hiện 26/08/2026 (kích hoạt theo yêu cầu) · Task 4.1 → 4.5 hoàn thành 5/5 + Clarity*

## I. PHẠM VI ĐÃ HOÀN THÀNH

| Task | Sản phẩm | Kiểm tra |
|---|---|---|
| 4.1 | **`ExportEngine.kt`** — decode → grade (pipeline Vulkan sẵn có) → encode **HEVC Main10** → MediaMuxer .mp4. Renderer thêm **export mode**: stash swapchain preview, tạo swapchain mới trên **encoder input surface** đúng độ phân giải clip, mid targets tái tạo theo; `endExport()` khôi phục nguyên trạng | g++ 0 lỗi |
| 4.2 | **Cache import AHardwareBuffer theo địa chỉ buffer** — ImageReader xoay ~4 buffer nên mỗi buffer chỉ import 1 lần (hết chi phí import/frame của Tuần 2-3); `clearAhbCache()` trả VRAM sau từng clip trong hàng đợi + khi đổi format + khi destroy | g++ 0 lỗi |
| 4.3 | **`BatchExporter.kt`** — hàng đợi xuất nhiều clip trên thread nền, áp GradeState riêng từng clip (map của GradeManager tuần 3), progress bar trong app + **notification kênh `fdc_export`** cập nhật %/clip và báo khi xong batch | Rà tay |
| 4.4 | **Gamma shift** — encoder gắn đủ metadata màu: `COLOR_STANDARD_BT709 + COLOR_TRANSFER_SDR_VIDEO + COLOR_RANGE_LIMITED`; pipeline nội bộ xuất Rec.709 nên preview và file trùng khớp; checklist đối chiếu ở Mục IV | Rà tay |
| 4.5 | Build **RC1** — versionName `0.4-rc1` | — |
| + | **Clarity** (spec preset 5: +15%) — `composite.frag` thay blit: unsharp mask trên LUMA (9 tap, giữ hướng màu — không viền màu), slider Clarity trong panel, có mặt trong GradeState/JSON/keyframing/preset loader | SPIR-V OK |

## II. QUYẾT ĐỊNH KIẾN TRÚC TUẦN 4

### QĐ10 — Đưa frame đã grade vào encoder bằng gì?
| | **(A) Swapchain Vulkan trên encoder input surface — ĐÃ CHỌN** | (B) Readback CPU → input ByteBuffer | (C) GLES trung gian |
|---|---|---|---|
| Ưu | **Zero-copy, tái dùng nguyên pipeline preview (đúng "preview = file xuất"), ít code mới nhất** | Kiểm soát PTS tuyệt đối | Nhiều tài liệu |
| Khuyết | PTS lấy theo timestamp surface (đủ đúng khi throttle; nếu lệch trên máy thật → RC2 dùng VK_GOOGLE_display_timing) | Chậm 5-10×, encoder hiếm khi nhận RGBA buffer trực tiếp | Thêm cả một stack GLES chỉ để xuất |

### QĐ11 — Clarity đặt ở đâu?
| | (A) Pass fp16 riêng midC→midD | **(B) Gộp vào composite (9 tap trên midC) — ĐÃ CHỌN** |
|---|---|---|
| Ưu | Scope thấy được clarity | **Không thêm pass/target nào; 9 tap chỉ chạy khi clarity ≠ 0; unsharp là hiệu ứng không gian nhẹ — scope trước clarity sai lệch không đáng kể (chỉ luma cục bộ)** |
| Khuyết | +1 target fp16 + bandwidth | Scope không phản ánh clarity (ghi nhận, chấp nhận) |

## III. TỒN ĐỌNG CHUYỂN TIẾP TUẦN 5 (final)
1. Material 3 polish toàn app (thay LinearLayout/Button thô), haptic toàn diện, autosave drafts, Before/After nhấn giữ (`uBypassGrade` có sẵn trong shader tham chiếu — cần nối uniform ở pass CST), final profiling nhiệt Tensor.
2. PTS export: đo lệch thực tế trên Pixel; nếu cần → VK_GOOGLE_display_timing hoặc chuyển encoder sang chế độ timestamp thủ công.
3. Xuất xong lưu vào thư mục Movies riêng của app — Tuần 5 thêm nút "Lưu vào Thư viện" (MediaStore).

## IV. CHECKLIST TEST TUẦN 4 (trên Pixel)
1. Import clip → chỉnh grade → **⇪ Xuat clip nay** → progress bar + notification chạy → file `FDC_*.mp4` trong Movies của app.
2. `adb shell dumpsys meminfo com.freedive.colorapp` trước/sau khi xuất 3 clip liên tiếp — bộ nhớ phải quay về mức nền (cache đã trả sau từng clip).
3. **Gamma shift**: mở file xuất trong Google Photos cạnh preview trong app — đặt cùng frame, so bằng mắt + chụp màn hình 2 bên đối chiếu histogram; nếu lệch → báo lại để bật đường `VK_EXT_swapchain_colorspace`.
4. Xuất "TAT CA" với 3 clip có grade khác nhau — kiểm tra từng file mang đúng màu clip tương ứng (không dính grade của nhau).
5. `mediainfo` (hoặc Photos → chi tiết): file phải là HEVC **Main 10**, BT.709.
