# PROFILING V1.0 — ĐO HIỆU NĂNG & NHIỆT TRÊN PIXEL (Task 5.4)

## 1. FPS preview
```bash
adb shell dumpsys SurfaceFlinger --latency SurfaceView   # khung hinh thuc te
# hoac Developer options → Profile HWUI rendering → thanh phai < 16.6ms (60fps)
```
Mở clip 4K30, bật Vectorscope, kéo slider liên tục 30 giây — không được rớt dưới 30fps.

## 2. Perfetto (GPU + CPU)
```bash
adb shell perfetto -o /data/misc/perfetto-traces/fdc.pftrace -t 30s \
  gfx sched freq idle wm -b 64mb
adb pull /data/misc/perfetto-traces/fdc.pftrace
# mo tai https://ui.perfetto.dev — soi track GPU va thread fdc-decode
```

## 3. Bộ nhớ (xác nhận cache AHB trả đúng — Task 4.2)
```bash
adb shell dumpsys meminfo com.freedive.colorapp | head -30
# Do TRUOC va SAU khi xuat batch 3 clip: Graphics/Native phai quay ve muc nen (+/- 10MB)
```

## 4. Nhiệt khi xuất batch
```bash
adb shell dumpsys thermalservice | grep -A3 "Thermal Status"
# Xuat 5 clip 4K lien tiep: khong duoc cham THERMAL_STATUS_SEVERE.
# Neu cham: giam BITRATE_4K trong ExportEngine.kt (60 -> 40 Mbps) hoac
# them sleep 2s giua cac clip trong BatchExporter (throttle chu dong).
```

## 5. Điểm nóng đã biết & ngưỡng chấp nhận
| Điểm | Hiện trạng | Khi nào cần xử lý |
|---|---|---|
| Clarity 9-tap trong composite | Early-out khi clarity=0 | Nếu 4K rớt fps khi clarity≠0 → giảm còn 5 tap |
| Scope compute (atomicAdd) | Subsample 4×4, chỉ chạy khi scope bật | Nếu ảnh hưởng fps → subsample 8×8 |
| PTS export theo surface timestamp | Đủ đúng khi encode realtime-throttled | File xuất sai tốc độ → VK_GOOGLE_display_timing |
| Import AHB | Cache theo buffer (Tuần 4) | Đã tối ưu — không còn re-import |

## 6. Gamma preview vs file xuất (Task 4.4)
Cùng một frame: chụp màn hình preview và mở file xuất trong Google Photos, đối chiếu bằng
Vectorscope trong app (import file xuất trở lại) — trace phải trùng vị trí.
