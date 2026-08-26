# HƯỚNG DẪN BUILD & XUẤT APK — V1.1-dev
*FreediveColorStudio · máy đích Google Pixel 10 Pro Max · cập nhật 26/08/2026*

> **Vì sao phải build trên máy anh:** môi trường cloud của Claude không có Android SDK
> và bị chặn mạng tới `dl.google.com` / `maven.google.com` / `services.gradle.org`
> (đã kiểm chứng), nên không thể compile APK từ xa. Bù lại, dự án đã được chuẩn hóa
> để Android Studio build **không cần thao tác tay nào ngoài bấm nút**: shader đã
> precompile SPIR-V sẵn, dependency chỉ 4 gói phổ thông, `gradle-wrapper.properties`
> đã khai báo Gradle 8.9 để Studio tự tải đúng bản.

## 1. Chuẩn bị (một lần, ~10 phút chủ yếu là chờ tải)
1. Cài **Android Studio** bản mới nhất → *SDK Manager* cài:
   - **SDK Platform API 36**
   - **NDK (Side by side)** — bản mặc định Studio đề xuất
   - **CMake 3.22.1+**
2. Giải nén `FreediveColorStudio-V1.1-dev.zip` → **File → Open** thư mục `FreediveColorStudio`.
3. Gradle sync chạy tự động. Hai hộp thoại có thể hiện ra — đều bấm đồng ý:
   - "Upgrade AGP/Kotlin" → **Accept** (root khai báo AGP 8.7.0/Kotlin 2.0.20, bản mới hơn đều được).
   - "NDK version not configured/khác" → chọn bản NDK đã cài ở bước 1.
4. Dependency tự tải khi sync (không cần làm gì): `material:1.12.0`, `activity-ktx`,
   `core-ktx`, `kotlinx-coroutines-android:1.8.1` (mới thêm cho Smart Guide).

## 2. Xuất APK
- **Build → Build App Bundle(s) / APK(s) → Build APK(s)** → APK nằm tại
  `app/build/outputs/apk/debug/app-debug.apk` (Studio hiện link "locate" khi xong).
- Cài lên máy: cắm cáp bật USB debugging rồi **Run ▶**, hoặc:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- Bản phát hành (ký release): **Build → Generate Signed App Bundle/APK** → chọn APK
  → tạo keystore → variant `release`. (Nội bộ thì bản debug là đủ để test.)

## 3. Ghi chú kỹ thuật đã lo sẵn (đọc khi gặp lỗi)
- **Shader**: toàn bộ `.spv` đã precompile và nằm trong `assets/shaders/` — build KHÔNG
  cần glslang. Chỉ khi anh tự sửa file `.frag/.comp` mới cần chạy lại:
  `glslangValidator -V <file> -o <file>.spv`.
- **`docs/compose-ref/`** nằm NGOÀI sourceSet — hai file Compose tham chiếu trong đó
  không được compile, app không cần dependency Compose.
- **CMake** tự build 4 file C++ (`lut_parser`, `lut_preview`, `vulkan_renderer`,
  `native_bridge`) và link `vulkan/android/log/nativewindow/mediandk/jnigraphics` —
  tất cả có sẵn trong NDK, không tải gì thêm.
- **`gradle/wrapper/gradle-wrapper.properties`** đã khai báo Gradle 8.9; Studio tự tải
  distribution này khi sync (file `gradle-wrapper.jar` chỉ cần cho dòng lệnh `./gradlew`
  — nếu muốn dùng CLI, chạy `gradle wrapper` một lần từ Studio Terminal).
- Máy chưa có clip D-Log M: app vẫn chạy với LUT identity; thumbnail LUT Library dùng
  gradient trung tính khi chưa mở clip.

## 4. SMOKE TEST 10 PHÚT SAU KHI CÀI (V1.0 + V1.1-dev)
1. **Mở clip** 🎬 (HEVC 10-bit từ DJI Action; file khác sẽ có toast cảnh báo) → preview chạy.
2. **5 preset** PQ/Cyan/Deep/Indo/Soc → màu đổi, slider nạp đúng giá trị.
3. **Scopes** 📊: Vectorscope → Waveform → tắt; kéo thả snap góc; chạm slider → bừng sáng.
4. **Before/After**: nhấn giữ player ≥0.2s → ảnh gốc; thả → grade trở lại.
5. **Smart Guide (V1.1)**: mở clip mới → hàng Temp/Tint/Anti-Green ĐẬP SÁNG + dòng gợi ý ①;
   kéo Tint > +0.20 → gợi ý chuyển sang ② EV/Shadows; tắt switch Pro Guide → hết highlight.
6. **Skin Lock (V1.1)**: preset Deep, kéo "Hue nước" −30 → da ám xanh; bật 🎭 → da hồi;
   bật "Xem mask" → da trắng/nước đen, biên mềm.
7. **LUT Library (V1.1)**: 🎨 → ＋ nhập nhiều `.cube` → thumbnail hiện theo frame clip thật;
   nhấn giữ → Đổi tên/Xóa; chọn LUT → preview đổi.
8. **Options + Xuất (V1.1)**: bật 💧 Logo → logo hiện ngay preview; thử 4 kiểu xuất:
   (a) mặc định HEVC10, (b) H.264 8-bit, (c) 🐢 Slow-Mo (clip 60fps → file dài ×2, không audio),
   (d) tắt 🔇 → file có audio gốc. File nằm trong `Android/data/com.freedive.colorapp/files/Movies/`.
9. **Autosave**: kill app → mở lại → gallery + grade khôi phục.
10. Đối chiếu màu preview vs file xuất (mục 6 của `PROFILING.md`).

## 5. Sau khi test
Dán vào phiên Claude: model máy, kết quả 10 mục trên, và `adb logcat -s FDC/Export FDC/Decoder FDC/Vk AndroidRuntime` nếu có lỗi → tôi sửa vòng V1.1 trên thiết bị thật.
