#version 310 es
/*
 * ============================================================================
 *  FREEDIVING COLOR — Fragment Shader (GLES 3.1 / tuong thich Vulkan qua SPIR-V)
 *  Pipeline 3 lop: Layer 1 Pre-LUT  ->  Layer 2 LUT Engine  ->  Layer 3 Post-LUT
 *  Nguon: COLOR GRADING PRESETS SPECIFICATION (DJI Action D-Log M 10-bit)
 *  Sinh ngay: 18/08/2026 — gia tri KHOP 100% voi freediving_color_presets.json
 *  va freediving_color_presets.h
 * ----------------------------------------------------------------------------
 *  LUU Y KY THUAT:
 *  - Clarity (+15% o preset 4) la phep loc KHONG GIAN (unsharp tren luma),
 *    phai chay o pass rieng sau shader nay; truong clarity giu trong struct
 *    de dong bo tham so nhung KHONG ap dung tai day.
 *  - Ham dlogMToLinear() dung duong cong XAP XI (placeholder). Khi co thong
 *    so D-Log M chinh thuc tu DJI, thay hang so trong ham nay hoac dung LUT
 *    ky thuat CST rieng.
 * ============================================================================
 */
precision highp float;
precision highp sampler3D;

in  vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uVideoTex;        // frame D-Log M da decode (FP16 khuyen nghi)
uniform sampler3D uLutTex;          // LUT .cube da nap (33^3 hoac 65^3)
uniform int   uLutSize;             // 33 hoac 65
uniform int   uPresetIndex;         // 0..4 — chon 1 trong 5 preset
uniform float uLutIntensityOverride;// <0.0: dung intensity cua preset; >=0.0: ghi de (slider LUT Mix)
uniform int   uBypassGrade;         // 1 = Before/After (tat toan bo hieu ung)
uniform float uAntiGreen;           // ANTI-GREEN (kieu LumaFusion): 0..1, 0 = tat.
                                    // Slider toan cuc, xep chong len moi preset.
/* VISIBILITY TOGGLE tung layer (icon con mat trong UI) — bypass hieu ung de so
 * sanh Before/After ma KHONG mat gia tri slider (spec Core Features muc 1).   */
uniform int   uLayer1On;            // 1 = bat Layer 1 Pre-LUT
uniform int   uLayer2On;            // 1 = bat Layer 2 LUT Engine
uniform int   uLayer3On;            // 1 = bat Layer 3 Post-LUT
/* DEPTH-BASED KEYFRAMING (spec Core Features muc 4): app noi suy tham so
 * frame-by-frame o phia CPU (fdc::Lerp trong freediving_color_presets.h) roi
 * day ket qua xuong qua UBO RuntimeParams o Tuan 3 — shader nay giu nguyen. */

/* ----------------------------- Cau truc preset ---------------------------- */
struct HSLBand   { float hue; float sat; float luma; };            // hue: degrees
struct PresetParams {
    float temperature;   // -1..+1  (+ = am/vang)
    float tint;          // -1..+1  (+ = magenta)
    float exposureEv;    // EV
    float contrast;      // -1..+1
    float shadowsLift;   // -1..+1
    float highlights;    // -1..+1
    float redRecovery;   // 0..1
    float magentaGuard;  // 1.0 = gioi han tint magenta o vung toi
    float lutIntensity;  // 0..1
    HSLBand hsl[7];      // R(0) O(30) Y(60) G(120) C(180) B(240) M(300)
    float globalSat;     // -1..+1
    float clarity;       // -1..+1 (pass rieng — khong dung o day)
    vec3  shadowTint;    // offset RGB vung toi (color wheel Shadows)
    float skinProtect;   // 1.0 = khoa hue/sat dai da (15..45 do)
};

const float HSL_CENTER[7] = float[7](0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 300.0);
const float HSL_WIDTH = 35.0;       // do rong gaussian moi band (degrees)

/* 5 preset — khop freediving_color_presets.json */
const PresetParams PRESETS[5] = PresetParams[5](
    /* 0 · Phu Quoc Deep Emerald (20-25m) */
    PresetParams(0.60, 0.50, 0.0, 0.0, 0.15, 0.0, 0.90, 1.0, 1.00,
        HSLBand[7](HSLBand(0.0,0.20,0.0), HSLBand(0.0,0.25,0.0), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,-0.20), HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0)),
        0.0, 0.0, vec3(0.0), 0.0),
    /* 1 · Tropical Cyan (Nha Trang / Quy Nhon, 5-10m) */
    PresetParams(-0.10, 0.10, 0.0, 0.0, 0.0, -0.20, 0.0, 0.0, 1.00,
        HSLBand[7](HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.15,-0.05), HSLBand(-10.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0)),
        0.0, 0.0, vec3(0.0), 0.0),
    /* 2 · True Deep Sea Blue (Phu Quy / Philippines, 30m+) */
    PresetParams(0.0, 0.0, 0.0, 0.15, 0.0, 0.0, 0.0, 0.0, 1.00,
        HSLBand[7](HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.10), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0), HSLBand(0.0,-0.10,-0.15),
                   HSLBand(0.0,0.0,0.0)),
        0.0, 0.0, vec3(0.0), 0.0),
    /* 3 · Indo Cinematic Moody (Bali / Lombok / Sumbawa) */
    PresetParams(0.0, 0.0, -0.5, -0.10, 0.0, 0.0, 0.40, 0.0, 1.00,
        HSLBand[7](HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0), HSLBand(-8.0,-0.20,0.0), HSLBand(-10.0,-0.20,0.0),
                   HSLBand(0.0,0.0,0.0)),
        -0.15, 0.0, vec3(-0.02, 0.015, 0.02), 0.0),
    /* 4 · Social Media Vibrant (Commercial Tour Standard) */
    PresetParams(0.0, 0.0, 0.2, 0.0, 0.0, 0.0, 0.60, 0.0, 0.85,
        HSLBand[7](HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0), HSLBand(0.0,0.0,0.0),
                   HSLBand(0.0,0.0,0.0)),
        0.10, 0.15, vec3(0.0), 1.0)
);

/* --------------------------- Ham tien ich mau ----------------------------- */
float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

/* XAP XI D-Log M -> Linear (PLACEHOLDER — thay hang so chinh thuc DJI) */
vec3 dlogMToLinear(vec3 c) {
    return pow(max(c - vec3(0.0625), vec3(0.0)) * (1.0 / 0.8737), vec3(1.0 / 0.45));
}
/* Linear -> Rec.709 OETF (gamma hien thi) */
vec3 linearToRec709(vec3 c) {
    c = max(c, vec3(0.0));
    return mix(c * 4.5, 1.099 * pow(c, vec3(0.45)) - 0.099, step(0.018, c));
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

/* ---------------------- ANTI-GREEN (kieu LumaFusion) ----------------------
 * Khu am xanh la (green cast) THICH NGHI theo pixel — chi tac dong len pixel
 * co kenh G troi hon ca R lan B (dac trung nuoc nhieu plankton), khong dung
 * den pixel xanh duong thuan (G < B) nen khong lam ban mau "Deep Sea Blue".
 * Bao toan luma: sau khi nen G, toan pixel duoc scale ve do sang goc de
 * anh khong bi toi di. Chay o Layer 1, sau WB/Tint, truoc Red Recovery.   */
vec3 applyAntiGreen(vec3 c, float strength) {
    if (strength <= 0.001) return c;
    float y0 = luma709(c);
    float gExcess = max(c.g - max(c.r, c.b), 0.0);   // phan G "thua" so voi trung tinh
    c.g -= strength * gExcess;                        // trung hoa green cast
    float y1 = luma709(c);
    c *= y0 / max(y1, 1e-5);                          // bao toan do sang
    return c;
}

/* ------------------------- LAYER 1 · PRE-LUT ------------------------------ */
vec3 applyPreLut(vec3 lin, PresetParams P) {
    // 1. Exposure (linear domain)
    lin *= exp2(P.exposureEv);

    // 2. White Balance & Tint (gain kenh don gian, du chinh xac cho grade UW)
    float Y = luma709(lin);
    float tintEff = P.tint;
    if (P.magentaGuard > 0.5) {
        // Chong nhieu magenta vung toi: giam luc tint theo luma (0 o den, du o midtone+)
        tintEff *= smoothstep(0.0, 0.35, Y);
    }
    lin.r *= 1.0 + 0.25 * P.temperature;
    lin.b *= 1.0 - 0.25 * P.temperature;
    lin.g *= 1.0 - 0.20 * tintEff;

    // 2b. ANTI-GREEN toan cuc (slider nguoi dung, doc lap voi preset)
    lin = applyAntiGreen(lin, uAntiGreen);

    // 3. Red Channel Recovery — keo lai kenh Do mat theo do sau:
    //    bu phan chenh giua "do ky vong" (tu G/B) va R hien tai, khong day qua nguong
    float refR = dot(lin.gb, vec2(0.7, 0.3));
    lin.r += P.redRecovery * 0.5 * max(refR - lin.r, 0.0);

    // 4. Chuyen sang Rec.709 (display-referred) de contrast/LUT hoat dong dung
    vec3 c = linearToRec709(lin);

    // 5. Contrast quanh pivot trung tinh
    c = mix(vec3(0.4353), c, 1.0 + P.contrast);

    // 6. Shadows lift / Highlights
    float y2 = luma709(c);
    c += P.shadowsLift * 0.25 * pow(1.0 - y2, 2.0);
    c += P.highlights  * 0.25 * pow(y2, 2.0);
    return clamp(c, 0.0, 1.0);
}

/* --------------------- LAYER 2 · LUT ENGINE (tetrahedral) ----------------- */
vec3 lutFetch(ivec3 p, int N) {
    p = clamp(p, ivec3(0), ivec3(N - 1));
    return texelFetch(uLutTex, p, 0).rgb;
}
/* Noi suy tu dien — triet tieu banding o vung gradient nuoc */
vec3 sampleLutTetrahedral(vec3 c, int N) {
    vec3  x  = c * float(N - 1);
    ivec3 i0 = ivec3(floor(x));
    vec3  f  = x - vec3(i0);
    ivec3 i1 = i0 + 1;

    vec3 c000 = lutFetch(i0, N);
    vec3 c111 = lutFetch(i1, N);
    vec3 res;
    if (f.r >= f.g && f.g >= f.b) {          // R > G > B
        vec3 c100 = lutFetch(ivec3(i1.x, i0.y, i0.z), N);
        vec3 c110 = lutFetch(ivec3(i1.x, i1.y, i0.z), N);
        res = (1.0-f.r)*c000 + (f.r-f.g)*c100 + (f.g-f.b)*c110 + f.b*c111;
    } else if (f.r >= f.b && f.b >= f.g) {   // R > B > G
        vec3 c100 = lutFetch(ivec3(i1.x, i0.y, i0.z), N);
        vec3 c101 = lutFetch(ivec3(i1.x, i0.y, i1.z), N);
        res = (1.0-f.r)*c000 + (f.r-f.b)*c100 + (f.b-f.g)*c101 + f.g*c111;
    } else if (f.b >= f.r && f.r >= f.g) {   // B > R > G
        vec3 c001 = lutFetch(ivec3(i0.x, i0.y, i1.z), N);
        vec3 c101 = lutFetch(ivec3(i1.x, i0.y, i1.z), N);
        res = (1.0-f.b)*c000 + (f.b-f.r)*c001 + (f.r-f.g)*c101 + f.g*c111;
    } else if (f.g >= f.r && f.r >= f.b) {   // G > R > B
        vec3 c010 = lutFetch(ivec3(i0.x, i1.y, i0.z), N);
        vec3 c110 = lutFetch(ivec3(i1.x, i1.y, i0.z), N);
        res = (1.0-f.g)*c000 + (f.g-f.r)*c010 + (f.r-f.b)*c110 + f.b*c111;
    } else if (f.g >= f.b && f.b >= f.r) {   // G > B > R
        vec3 c010 = lutFetch(ivec3(i0.x, i1.y, i0.z), N);
        vec3 c011 = lutFetch(ivec3(i0.x, i1.y, i1.z), N);
        res = (1.0-f.g)*c000 + (f.g-f.b)*c010 + (f.b-f.r)*c011 + f.r*c111;
    } else {                                  // B > G > R
        vec3 c001 = lutFetch(ivec3(i0.x, i0.y, i1.z), N);
        vec3 c011 = lutFetch(ivec3(i0.x, i1.y, i1.z), N);
        res = (1.0-f.b)*c000 + (f.b-f.g)*c001 + (f.g-f.r)*c011 + f.r*c111;
    }
    return res;
}
vec3 applyLutEngine(vec3 c, PresetParams P) {
    float k = (uLutIntensityOverride >= 0.0) ? uLutIntensityOverride : P.lutIntensity;
    if (k <= 0.001) return c;
    vec3 graded = sampleLutTetrahedral(c, uLutSize);
    return mix(c, graded, k);               // LUT Mix / Intensity 0..100%
}

/* ------------------------- LAYER 3 · POST-LUT ----------------------------- */
float bandWeight(float hueDeg, float center) {
    float d = abs(hueDeg - center);
    d = min(d, 360.0 - d);                   // khoang cach hue co wrap
    float t = d / HSL_WIDTH;
    return exp(-t * t);                      // gaussian
}
vec3 applyPostLut(vec3 c, PresetParams P) {
    vec3 hsv = rgb2hsv(c);
    float hueDeg = hsv.x * 360.0;
    bool skinBand = (hueDeg >= 15.0 && hueDeg <= 45.0);

    float hueShift = 0.0, satMul = 1.0, lumaAdd = 0.0;
    for (int i = 0; i < 7; i++) {
        float w = bandWeight(hueDeg, HSL_CENTER[i]);
        hueShift += w * P.hsl[i].hue;
        satMul   *= 1.0 + w * P.hsl[i].sat;
        lumaAdd  += w * P.hsl[i].luma * 0.30;
    }
    // Skin Tone Protection: khoa hue-shift, gioi han sat trong dai da
    if (P.skinProtect > 0.5 && skinBand) {
        hueShift = 0.0;
        satMul = clamp(satMul, 0.95, 1.08);
    }
    hsv.x = fract(hsv.x + hueShift / 360.0 + 1.0);
    hsv.y = clamp(hsv.y * satMul, 0.0, 1.0);
    hsv.z = clamp(hsv.z * (1.0 + lumaAdd), 0.0, 1.0);
    c = hsv2rgb(hsv);

    // Global Saturation
    float Y = luma709(c);
    c = mix(vec3(Y), c, 1.0 + P.globalSat);

    // Shadow tint (color wheel vung toi — vi du teal cua Indo Moody)
    c += P.shadowTint * pow(1.0 - Y, 2.0);

    // Clarity: XU LY O PASS RIENG (unsharp mask tren luma) — xem ghi chu dau file
    return clamp(c, 0.0, 1.0);
}

/* --------------------------------- MAIN ----------------------------------- */
void main() {
    vec3 src = texture(uVideoTex, vTexCoord).rgb;      // D-Log M encoded
    if (uBypassGrade == 1) {                            // Before/After (nhan giu)
        fragColor = vec4(linearToRec709(dlogMToLinear(src)), 1.0);
        return;
    }
    PresetParams P = PRESETS[clamp(uPresetIndex, 0, 4)];
    vec3 lin = dlogMToLinear(src);                      // CST: D-Log M -> Linear
    vec3 c;
    if (uLayer1On == 1) { c = applyPreLut(lin, P); }    // Layer 1 (co eye-toggle)
    else                { c = clamp(linearToRec709(lin), 0.0, 1.0); }
    if (uLayer2On == 1) { c = applyLutEngine(c, P); }   // Layer 2 (co eye-toggle)
    if (uLayer3On == 1) { c = applyPostLut(c, P); }     // Layer 3 (co eye-toggle)
    fragColor = vec4(c, 1.0);
}
