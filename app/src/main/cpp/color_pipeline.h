// ============================================================================
//  FREEDIVING COLOR — Preset definitions (C++17, Android NDK)
//  Pipeline 3 lop: Pre-LUT -> LUT Engine (.cube, tetrahedral) -> Post-LUT
//  Nguon: COLOR GRADING PRESETS SPECIFICATION (DJI Action D-Log M 10-bit)
//  Sinh ngay: 18/08/2026 — gia tri KHOP 100% voi freediving_color_presets.json
//  va freediving_color.frag. Dung de nap uniform/UBO cho Vulkan/GLES.
// ============================================================================
#pragma once
#include <cstdint>

namespace fdc {

// ----------------------------- Quy uoc don vi -------------------------------
//  temperature : -1..+1  (+ = am ap/vang, - = lanh/xanh duong)
//  tint        : -1..+1  (+ = magenta,   - = green)
//  exposure_ev : EV stop
//  contrast / shadows_lift / highlights / saturation / luma : -1..+1 (spec %/100)
//  red_recovery, lut_intensity : 0..1
//  hue_deg     : degrees (am = xoay ve phia cyan/teal)
// ----------------------------------------------------------------------------

struct HSLBand {
    float hue_deg;
    float saturation;
    float luma;
};

// 7 kenh HSL: mo rong tu 6 kenh (kien truc goc) de giu nguyen gia tri "Orange"
// trong spec preset — DEVIATION da ghi nhan trong tai lieu.
enum class HSLChannel : uint8_t { Red = 0, Orange, Yellow, Green, Cyan, Blue, Magenta, COUNT };
inline constexpr int kHSLBandCount = static_cast<int>(HSLChannel::COUNT);

// Tam hue cua tung band (degrees) — khop HSL_CENTER trong freediving_color.frag
inline constexpr float kHSLCenterDeg[kHSLBandCount] = {0.f, 30.f, 60.f, 120.f, 180.f, 240.f, 300.f};
inline constexpr float kHSLBandWidthDeg = 35.f;   // do rong gaussian

struct PreLutParams {                 // LAYER 1 — truoc khi ap LUT
    float white_balance_temperature;
    float tint;
    float exposure_ev;
    float contrast;
    float shadows_lift;
    float highlights;
    float red_channel_recovery;       // keo lai kenh Do mat theo do sau
    bool  magenta_shadow_guard;       // chong nhieu magenta o vung toi (Preset 1)
};

struct LutEngineParams {              // LAYER 2 — loi Grade Co
    float intensity;                  // LUT Mix 0..1 (noi suy tetrahedral)
};

// TASK S2 — SKIN-TONE LOCK MASK: uniform data day xuong shader (spec muc 4).
// Mask HSV chay tren GPU trong post_lut.frag; cac gia tri mac dinh do tren
// footage da nguoi duoi nuoc nhiet doi 5-25m (hue da lech ve orange/red).
struct SkinMaskParams {
    float target_hue_deg = 25.f;      // hue da muc tieu (0..60 — orange/red)
    float tolerance_deg  = 18.f;      // loi bao ve tron ven (do)
    float feather_deg    = 22.f;      // vung chuyen smoothstep (do) — khong ria cung
    float strength       = 1.f;       // 0..1 muc do khoa
    bool  enabled        = false;
    bool  mask_view      = false;     // debug: xuat mask grayscale
    float sat_gate_lo    = 0.12f;     // duoi nguong sat nay coi nhu KHONG phai da
    float val_gate_lo    = 0.06f;     // duoi nguong value nay (gan den) khong khoa
};

struct PostLutParams {                // LAYER 3 — tinh chinh tham my
    HSLBand hsl[kHSLBandCount];       // thu tu theo HSLChannel
    float   global_saturation;
    float   clarity;                  // ap dung o pass rieng (unsharp tren luma)
    float   shadow_tint_rgb[3];       // color wheel vung toi
    bool    skin_tone_protection;     // khoa hue/sat dai da 15..45 do (Preset 5)
    SkinMaskParams skin_mask;         // TASK S2 — Skin-Tone Lock Mask (GPU)
};

struct ColorPreset {
    const char*     id;
    const char*     display_name;
    PreLutParams    pre_lut;
    LutEngineParams lut_engine;
    PostLutParams   post_lut;
};

// ============================ 5 PRESET CHINH THUC ===========================
inline constexpr ColorPreset kPresets[] = {

    // 0 · PHU QUOC DEEP EMERALD (20-25m) — am xanh la nang, mat pho do nghiem trong
    {
        "phu_quoc_deep_emerald", "Phu Quoc Deep Emerald",
        /*pre */ { .white_balance_temperature = 0.60f, .tint = 0.50f,
                   .exposure_ev = 0.0f,  .contrast = 0.0f,
                   .shadows_lift = 0.15f, .highlights = 0.0f,
                   .red_channel_recovery = 0.90f,      // spec: +80%..+95% (max)
                   .magenta_shadow_guard = true },
        /*lut */ { .intensity = 1.00f },
        /*post*/ { .hsl = { {0.f, 0.20f, 0.f},         // Red    : sat +20%
                            {0.f, 0.25f, 0.f},         // Orange : sat +25% (tone da)
                            {0.f, 0.f,   0.f},         // Yellow
                            {0.f, 0.f,  -0.20f},       // Green  : luma -20% (toi nen nuoc)
                            {0.f, 0.f,   0.f},         // Cyan
                            {0.f, 0.f,   0.f},         // Blue
                            {0.f, 0.f,   0.f} },       // Magenta
                   .global_saturation = 0.0f, .clarity = 0.0f,
                   .shadow_tint_rgb = {0.f, 0.f, 0.f},
                   .skin_tone_protection = false }
    },

    // 1 · TROPICAL CYAN (Nha Trang / Quy Nhon, 5-10m) — nuoc nong, nang xuyen manh
    {
        "tropical_cyan", "Tropical Cyan (Nha Trang / Quy Nhon)",
        /*pre */ { .white_balance_temperature = -0.10f, .tint = 0.10f,
                   .exposure_ev = 0.0f, .contrast = 0.0f,
                   .shadows_lift = 0.0f, .highlights = -0.20f,   // giu chi tiet sunbeam
                   .red_channel_recovery = 0.0f,
                   .magenta_shadow_guard = false },
        /*lut */ { .intensity = 1.00f },
        /*post*/ { .hsl = { {0.f,   0.f,   0.f},
                            {0.f,   0.f,   0.f},
                            {0.f,   0.f,   0.f},
                            {0.f,   0.f,   0.f},
                            {0.f,   0.15f, -0.05f},    // Cyan : sat +15%, luma -5%
                            {-10.f, 0.f,   0.f},       // Blue : hue xoay ve Cyan
                            {0.f,   0.f,   0.f} },
                   .global_saturation = 0.0f, .clarity = 0.0f,
                   .shadow_tint_rgb = {0.f, 0.f, 0.f},
                   .skin_tone_protection = false }
    },

    // 2 · TRUE DEEP SEA BLUE (Phu Quy / Philippines, 30m+) — pelagic cuc trong
    {
        "true_deep_sea_blue", "True Deep Sea Blue (Phu Quy / Philippines)",
        /*pre */ { .white_balance_temperature = 0.0f, .tint = 0.0f,
                   .exposure_ev = 0.0f, .contrast = 0.15f,       // +15% punch cho D-Log M
                   .shadows_lift = 0.0f, .highlights = 0.0f,
                   .red_channel_recovery = 0.0f,
                   .magenta_shadow_guard = false },
        /*lut */ { .intensity = 1.00f },
        /*post*/ { .hsl = { {0.f, 0.f,    0.f},
                            {0.f, 0.f,    0.10f},      // Orange : luma +10% (diver pop)
                            {0.f, 0.f,    0.f},
                            {0.f, 0.f,    0.f},
                            {0.f, 0.f,    0.f},
                            {0.f, -0.10f, -0.15f},     // Blue : sat -10% (chong neon), luma -15%
                            {0.f, 0.f,    0.f} },
                   .global_saturation = 0.0f, .clarity = 0.0f,
                   .shadow_tint_rgb = {0.f, 0.f, 0.f},
                   .skin_tone_protection = false }
    },

    // 3 · INDO CINEMATIC MOODY (Bali / Lombok / Sumbawa) — chat phim, moody, HDR
    {
        "indo_cinematic_moody", "Indo Cinematic Moody (Bali / Lombok / Sumbawa)",
        /*pre */ { .white_balance_temperature = 0.0f, .tint = 0.0f,
                   .exposure_ev = -0.5f,               // -0.5 stop
                   .contrast = -0.10f,                 // nen filmic phang hon
                   .shadows_lift = 0.0f, .highlights = 0.0f,
                   .red_channel_recovery = 0.40f,      // moderate
                   .magenta_shadow_guard = false },
        /*lut */ { .intensity = 1.00f },
        /*post*/ { .hsl = { {0.f,   0.f,    0.f},
                            {0.f,   0.f,    0.f},
                            {0.f,   0.f,    0.f},
                            {0.f,   0.f,    0.f},
                            {-8.f,  -0.20f, 0.f},      // Cyan : ve teal, sat -20%
                            {-10.f, -0.20f, 0.f},      // Blue : ve teal, sat -20%
                            {0.f,   0.f,    0.f} },
                   .global_saturation = -0.15f, .clarity = 0.0f,
                   .shadow_tint_rgb = {-0.02f, 0.015f, 0.02f},   // teal nhe vung toi
                   .skin_tone_protection = false }
    },

    // 4 · SOCIAL MEDIA VIBRANT (Commercial Tour Standard) — van nang, CapCut-friendly
    {
        "social_media_vibrant", "Social Media Vibrant (Commercial Tour Standard)",
        /*pre */ { .white_balance_temperature = 0.0f, .tint = 0.0f,
                   .exposure_ev = 0.2f,                // bright & airy
                   .contrast = 0.0f,
                   .shadows_lift = 0.0f, .highlights = 0.0f,
                   .red_channel_recovery = 0.60f,      // "khach khoe manh, khong hypoxic"
                   .magenta_shadow_guard = false },
        /*lut */ { .intensity = 0.85f },               // Grade Co clamp 85%
        /*post*/ { .hsl = { {0.f, 0.f, 0.f}, {0.f, 0.f, 0.f}, {0.f, 0.f, 0.f},
                            {0.f, 0.f, 0.f}, {0.f, 0.f, 0.f}, {0.f, 0.f, 0.f},
                            {0.f, 0.f, 0.f} },
                   .global_saturation = 0.10f,
                   .clarity = 0.15f,                   // ap dung o pass unsharp rieng
                   .shadow_tint_rgb = {0.f, 0.f, 0.f},
                   .skin_tone_protection = true }      // bao ve dai da 15..45 do
    },
};

inline constexpr int kPresetCount = static_cast<int>(sizeof(kPresets) / sizeof(kPresets[0]));
static_assert(kPresetCount == 5, "Phai co dung 5 preset theo spec");

// ===================== ANTI-GREEN (kieu LumaFusion) =========================
// Slider TOAN CUC 0..1, xep chong len moi preset (khong thuoc PresetParams).
// Thuat toan (khop uniform uAntiGreen trong freediving_color.frag):
//   gExcess = max(G - max(R, B), 0)        — phan G "thua" so voi trung tinh
//   G'      = G - strength * gExcess       — trung hoa green cast
//   scale toan pixel ve luma goc           — bao toan do sang
// Chi tac dong pixel co G troi (nuoc nhieu plankton); pixel xanh duong thuan
// (G < B) khong doi -> an toan cho look "True Deep Sea Blue".
// Vi tri pipeline: Layer 1 Pre-LUT, sau WB/Tint, truoc Red Channel Recovery.

struct AntiGreenControl {
    float strength;                    // 0..1; 0 = tat
};

inline constexpr float kAntiGreenDefault     = 0.0f;   // mac dinh tat
inline constexpr float kAntiGreenQuickToggle = 0.5f;   // gia tri khi bam chip bat nhanh

// Muc khoi diem DE XUAT theo preset (tinh chinh khi test footage that):
// Phu Quoc da bu green manh bang Tint +0.50 nen chi can thap; Deep Sea Blue
// khong co green cast nen de 0.
inline constexpr float kAntiGreenRecommended[kPresetCount] = {
    0.15f,   // phu_quoc_deep_emerald
    0.30f,   // tropical_cyan
    0.00f,   // true_deep_sea_blue
    0.20f,   // indo_cinematic_moody
    0.25f,   // social_media_vibrant
};

// ================= VISIBILITY TOGGLE 3 LAYER (Core Features #1) =============
// Icon con mat tren tung layer: bypass hieu ung de so sanh Before/After ma
// khong mat gia tri slider. Map sang uniform uLayer1On/2On/3On trong shader.
struct LayerVisibility {
    bool layer1_pre_lut  = true;
    bool layer2_lut      = true;
    bool layer3_post_lut = true;
};

// ============= DEPTH-BASED KEYFRAMING (Core Features #4) ====================
// Clip lan tu day (25m) len mat nuoc: grade tinh se "gay" mau. Nguoi dung dat
// keyframe dau/cuoi clip (vd Red Recovery 0.80 -> 0.10); engine noi suy
// frame-by-frame o CPU roi day tham so xuong GPU qua UBO (trien khai Tuan 3).
struct GradeKeyframe {
    double        time_sec;      // vi tri keyframe tren clip
    PreLutParams  pre_lut;       // toan bo Layer 1 keyframe duoc
    float         lut_intensity; // Layer 2
    PostLutParams post_lut;      // Layer 3
    float         anti_green;    // slider toan cuc cung noi suy duoc
};

constexpr float Lerp(float a, float b, float t) { return a + (b - a) * t; }

// Noi suy tuyen tinh tung truong (bool giu gia tri keyframe A — khong noi suy)
constexpr PreLutParams Lerp(const PreLutParams& a, const PreLutParams& b, float t) {
    return PreLutParams{
        Lerp(a.white_balance_temperature, b.white_balance_temperature, t),
        Lerp(a.tint,                      b.tint,                      t),
        Lerp(a.exposure_ev,               b.exposure_ev,               t),
        Lerp(a.contrast,                  b.contrast,                  t),
        Lerp(a.shadows_lift,              b.shadows_lift,              t),
        Lerp(a.highlights,                b.highlights,                t),
        Lerp(a.red_channel_recovery,      b.red_channel_recovery,      t),
        a.magenta_shadow_guard,
    };
}
constexpr HSLBand Lerp(const HSLBand& a, const HSLBand& b, float t) {
    return HSLBand{ Lerp(a.hue_deg, b.hue_deg, t),
                    Lerp(a.saturation, b.saturation, t),
                    Lerp(a.luma, b.luma, t) };
}
constexpr PostLutParams Lerp(const PostLutParams& a, const PostLutParams& b, float t) {
    return PostLutParams{
        { Lerp(a.hsl[0], b.hsl[0], t), Lerp(a.hsl[1], b.hsl[1], t),
          Lerp(a.hsl[2], b.hsl[2], t), Lerp(a.hsl[3], b.hsl[3], t),
          Lerp(a.hsl[4], b.hsl[4], t), Lerp(a.hsl[5], b.hsl[5], t),
          Lerp(a.hsl[6], b.hsl[6], t) },
        Lerp(a.global_saturation, b.global_saturation, t),
        Lerp(a.clarity,           b.clarity,           t),
        { Lerp(a.shadow_tint_rgb[0], b.shadow_tint_rgb[0], t),
          Lerp(a.shadow_tint_rgb[1], b.shadow_tint_rgb[1], t),
          Lerp(a.shadow_tint_rgb[2], b.shadow_tint_rgb[2], t) },
        a.skin_tone_protection,
    };
}
// Danh gia trang thai grade tai thoi diem time_sec giua 2 keyframe ke nhau
inline GradeKeyframe Evaluate(const GradeKeyframe& k0, const GradeKeyframe& k1, double time_sec) {
    const double span = k1.time_sec - k0.time_sec;
    const float  t = (span > 0.0)
        ? static_cast<float>((time_sec - k0.time_sec) / span < 0.0 ? 0.0
             : ((time_sec - k0.time_sec) / span > 1.0 ? 1.0 : (time_sec - k0.time_sec) / span))
        : 0.0f;
    return GradeKeyframe{ time_sec,
                          Lerp(k0.pre_lut, k1.pre_lut, t),
                          Lerp(k0.lut_intensity, k1.lut_intensity, t),
                          Lerp(k0.post_lut, k1.post_lut, t),
                          Lerp(k0.anti_green, k1.anti_green, t) };
}

}  // namespace fdc
