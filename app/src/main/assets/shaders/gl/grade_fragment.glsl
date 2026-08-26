#version 300 es
// ============================================================================
//  FREEDIVE COLOR — PASS GRADE (GL ES 3.0)
//  Ban port cua 3 pass Vulkan goc, GOP LAM MOT vi ca ba deu thuan tuy per-pixel:
//      color_space.frag     (CST D-Log M -> Rec.709 + Layer 1)
//    + lut_tetrahedral.frag (Layer 2 — noi suy tu dien tren LUT 3D)
//    + post_lut.frag        (Layer 3 — HSL 7 kenh, Global Sat, Skin Lock Mask)
//  Clarity KHONG gop vao day: no can lay mau 8 diem lan can cua ANH DA GRADE,
//  neu gop se phai chay lai ca chuoi 9 lan/pixel. Clarity o effect rieng.
//
//  Khac biet so voi ban Vulkan:
//   · push_constant / UBO  -> uniform roi rac (GL ES khong co push constant)
//   · mang uniform vec4[7] -> 7 uniform vec3 rieng (GlProgram cua Media3
//     khong co setter cho mang vector)
//   · Cong thuc mau giu NGUYEN VEN 100% de anh xuat khong lech so voi ban cu.
// ============================================================================
precision highp float;
precision highp sampler3D;

uniform sampler2D uTexSampler;   // frame vao (Media3 da chuyen ve texture 2D thuong)
uniform sampler3D uLutTex;       // LUT .cube 33^3 / 65^3

// ---- Layer 1 ----
uniform float uTemp;
uniform float uTint;
uniform float uEv;
uniform float uContrast;
uniform float uShadows;
uniform float uHighlights;
uniform float uRedRecovery;
uniform float uAntiGreen;
uniform float uMagentaGuard;
uniform float uL1On;

// ---- Layer 2 ----
uniform float uLutIntensity;
uniform float uLutSize;
uniform float uL2On;

// ---- Layer 3 ----
uniform vec3  uHsl0;   // R   (hueDeg, sat, luma)
uniform vec3  uHsl1;   // O
uniform vec3  uHsl2;   // Y
uniform vec3  uHsl3;   // G
uniform vec3  uHsl4;   // C
uniform vec3  uHsl5;   // B
uniform vec3  uHsl6;   // M
uniform float uGlobalSat;
uniform float uSkinProtect;
uniform float uL3On;
uniform vec3  uShadowTint;
uniform vec4  uSkinMask;    // (targetHueDeg, toleranceDeg, featherDeg, strength)
uniform vec4  uSkinMask2;   // (enable, maskView, satGateLo, valGateLo)

in  vec2 vTexSamplingCoord;
out vec4 outColor;

const float HSL_WIDTH = 35.0;

float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ---------------------------------------------------------------- LAYER 1 ---
vec3 dlogMToLinear(vec3 c) {
    return pow(max(c - vec3(0.0625), vec3(0.0)) * (1.0 / 0.8737), vec3(1.0 / 0.45));
}
vec3 linearToRec709(vec3 c) {
    c = max(c, vec3(0.0));
    return mix(c * 4.5, 1.099 * pow(c, vec3(0.45)) - 0.099, step(vec3(0.018), c));
}

vec3 applyLayer1(vec3 srcRgb) {
    vec3 lin = dlogMToLinear(srcRgb);
    if (uL1On < 0.5) {
        return clamp(linearToRec709(lin), 0.0, 1.0);
    }
    // 1. Exposure (linear)
    lin *= exp2(uEv);

    // 2. White Balance & Tint (guard chong nhieu magenta vung toi)
    float tintEff = uTint;
    if (uMagentaGuard > 0.5) tintEff *= smoothstep(0.0, 0.35, luma709(lin));
    lin.r *= 1.0 + 0.25 * uTemp;
    lin.b *= 1.0 - 0.25 * uTemp;
    lin.g *= 1.0 - 0.20 * tintEff;

    // 3. Anti-Green thich nghi, bao toan luma
    if (uAntiGreen > 0.001) {
        float y0 = luma709(lin);
        lin.g -= uAntiGreen * max(lin.g - max(lin.r, lin.b), 0.0);
        lin *= y0 / max(luma709(lin), 1e-5);
    }

    // 4. Red Channel Recovery
    float refR = dot(lin.gb, vec2(0.7, 0.3));
    lin.r += uRedRecovery * 0.5 * max(refR - lin.r, 0.0);

    // 5. Rec.709 + Contrast (pivot trung tinh) + Shadows/Highlights
    vec3 c = linearToRec709(lin);
    c = mix(vec3(0.4353), c, 1.0 + uContrast);
    float y = luma709(c);
    c += uShadows    * 0.25 * pow(1.0 - y, 2.0);
    c += uHighlights * 0.25 * pow(y, 2.0);
    return clamp(c, 0.0, 1.0);
}

// ---------------------------------------------------------------- LAYER 2 ---
vec3 lutFetch(ivec3 p, int N) {
    p = clamp(p, ivec3(0), ivec3(N - 1));
    return texelFetch(uLutTex, p, 0).rgb;
}

vec3 sampleLutTetrahedral(vec3 c, int N) {
    vec3  x  = clamp(c, 0.0, 1.0) * float(N - 1);
    ivec3 i0 = ivec3(floor(min(x, vec3(float(N - 2)))));
    vec3  f  = x - vec3(i0);
    ivec3 i1 = i0 + 1;

    vec3 c000 = lutFetch(i0, N);
    vec3 c111 = lutFetch(i1, N);
    vec3 res;
    if (f.r >= f.g && f.g >= f.b) {
        vec3 c100 = lutFetch(ivec3(i1.x, i0.y, i0.z), N);
        vec3 c110 = lutFetch(ivec3(i1.x, i1.y, i0.z), N);
        res = (1.0-f.r)*c000 + (f.r-f.g)*c100 + (f.g-f.b)*c110 + f.b*c111;
    } else if (f.r >= f.b && f.b >= f.g) {
        vec3 c100 = lutFetch(ivec3(i1.x, i0.y, i0.z), N);
        vec3 c101 = lutFetch(ivec3(i1.x, i0.y, i1.z), N);
        res = (1.0-f.r)*c000 + (f.r-f.b)*c100 + (f.b-f.g)*c101 + f.g*c111;
    } else if (f.b >= f.r && f.r >= f.g) {
        vec3 c001 = lutFetch(ivec3(i0.x, i0.y, i1.z), N);
        vec3 c101 = lutFetch(ivec3(i1.x, i0.y, i1.z), N);
        res = (1.0-f.b)*c000 + (f.b-f.r)*c001 + (f.r-f.g)*c101 + f.g*c111;
    } else if (f.g >= f.r && f.r >= f.b) {
        vec3 c010 = lutFetch(ivec3(i0.x, i1.y, i0.z), N);
        vec3 c110 = lutFetch(ivec3(i1.x, i1.y, i0.z), N);
        res = (1.0-f.g)*c000 + (f.g-f.r)*c010 + (f.r-f.b)*c110 + f.b*c111;
    } else if (f.g >= f.b && f.b >= f.r) {
        vec3 c010 = lutFetch(ivec3(i0.x, i1.y, i0.z), N);
        vec3 c011 = lutFetch(ivec3(i0.x, i1.y, i1.z), N);
        res = (1.0-f.g)*c000 + (f.g-f.b)*c010 + (f.b-f.r)*c011 + f.r*c111;
    } else {
        vec3 c001 = lutFetch(ivec3(i0.x, i0.y, i1.z), N);
        vec3 c011 = lutFetch(ivec3(i0.x, i1.y, i1.z), N);
        res = (1.0-f.b)*c000 + (f.b-f.g)*c001 + (f.g-f.r)*c011 + f.r*c111;
    }
    return res;
}

vec3 applyLayer2(vec3 src) {
    if (uL2On < 0.5 || uLutIntensity <= 0.001 || uLutSize < 2.0) return src;
    vec3 graded = sampleLutTetrahedral(src, int(uLutSize));
    return mix(src, graded, clamp(uLutIntensity, 0.0, 1.0));
}

// ---------------------------------------------------------------- LAYER 3 ---
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

float bandWeight(float hueDeg, float center) {
    float d = abs(hueDeg - center);
    d = min(d, 360.0 - d);
    float t = d / HSL_WIDTH;
    return exp(-t * t);
}

float skinLockWeight(vec3 hsvIn) {
    float hueDeg = hsvIn.x * 360.0;
    float d = abs(hueDeg - uSkinMask.x);
    d = min(d, 360.0 - d);
    float w = 1.0 - smoothstep(uSkinMask.y, uSkinMask.y + uSkinMask.z, d);
    w *= smoothstep(uSkinMask2.z, uSkinMask2.z + 0.10, hsvIn.y);
    w *= smoothstep(uSkinMask2.w, uSkinMask2.w + 0.08, hsvIn.z);
    return clamp(w, 0.0, 1.0);
}

void main() {
    vec3 src = texture(uTexSampler, vTexSamplingCoord).rgb;

    vec3 c = applyLayer1(src);
    c = applyLayer2(c);

    // ---- Layer 3 ----
    vec3 cIn = c;
    if (uL3On < 0.5) {
        outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        return;
    }

    vec3 hsvIn = rgb2hsv(cIn);
    float lockW = (uSkinMask2.x > 0.5) ? skinLockWeight(hsvIn) * uSkinMask.w : 0.0;
    if (uSkinMask2.y > 0.5) {                 // che do xem mask (debug)
        outColor = vec4(vec3(lockW), 1.0);
        return;
    }

    vec3 hsv = hsvIn;
    float hueDeg = hsv.x * 360.0;
    bool skinBand = (hueDeg >= 15.0 && hueDeg <= 45.0);

    float hueShift = 0.0;
    float satMul = 1.0;
    float lumaAdd = 0.0;

    float w;
    w = bandWeight(hueDeg,   0.0); hueShift += w * uHsl0.x; satMul *= 1.0 + w * uHsl0.y; lumaAdd += w * uHsl0.z * 0.30;
    w = bandWeight(hueDeg,  30.0); hueShift += w * uHsl1.x; satMul *= 1.0 + w * uHsl1.y; lumaAdd += w * uHsl1.z * 0.30;
    w = bandWeight(hueDeg,  60.0); hueShift += w * uHsl2.x; satMul *= 1.0 + w * uHsl2.y; lumaAdd += w * uHsl2.z * 0.30;
    w = bandWeight(hueDeg, 120.0); hueShift += w * uHsl3.x; satMul *= 1.0 + w * uHsl3.y; lumaAdd += w * uHsl3.z * 0.30;
    w = bandWeight(hueDeg, 180.0); hueShift += w * uHsl4.x; satMul *= 1.0 + w * uHsl4.y; lumaAdd += w * uHsl4.z * 0.30;
    w = bandWeight(hueDeg, 240.0); hueShift += w * uHsl5.x; satMul *= 1.0 + w * uHsl5.y; lumaAdd += w * uHsl5.z * 0.30;
    w = bandWeight(hueDeg, 300.0); hueShift += w * uHsl6.x; satMul *= 1.0 + w * uHsl6.y; lumaAdd += w * uHsl6.z * 0.30;

    if (uSkinProtect > 0.5 && skinBand) {
        hueShift = 0.0;
        satMul = clamp(satMul, 0.95, 1.08);
    }
    hsv.x = fract(hsv.x + hueShift / 360.0 + 1.0);
    hsv.y = clamp(hsv.y * satMul, 0.0, 1.0);
    hsv.z = clamp(hsv.z * (1.0 + lumaAdd), 0.0, 1.0);
    c = hsv2rgb(hsv);

    float Y = luma709(c);
    c = mix(vec3(Y), c, 1.0 + uGlobalSat);
    c += uShadowTint * pow(1.0 - Y, 2.0);

    c = mix(c, cIn, lockW);

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
