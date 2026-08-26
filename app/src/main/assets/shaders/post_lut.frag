#version 450
/*
 * ============================================================================
 *  TASK 3.1 + S2 — LAYER 3 POST-LUT · Vulkan GLSL (PASS 3: midB -> midC)
 *  HSL 7 kenh (R/O/Y/G/C/B/M) + Global Saturation + Shadow Tint + Skin Tone
 *  Protection + SKIN-TONE LOCK MASK (S2). Tham so qua UBO 176B (std140) —
 *  cap nhat moi frame tu CPU, keyframing noi suy roi ghi vao cung UBO.
 *  Clarity (unsharp khong gian) van o pass rieng — Tuan 4.
 *
 *  S2 — SKIN-TONE LOCK MASK (chay 100% tren GPU, khong them pass):
 *  Grade nuoc sau thuong keo Cyan/Blue rat manh -> pha tone da tho lan.
 *  Mask tinh tu MAU DAU VAO cua pass (truoc moi chinh sua Layer 3):
 *    1. RGB -> HSV; do lech hue TRON (wrap 360) toi hue da muc tieu
 *       (mac dinh 25 do — vung orange/red; da duoi nuoc lech dan ve do/magenta
 *       theo do sau nen hue muc tieu cho chinh 0..60).
 *    2. Loi bao ve tron ven: lech <= tolerance -> mask = 1.
 *       Vung chuyen: 1 - smoothstep(tol, tol + feather, lech) — falloff MUOT,
 *       khong bao gio ra ria pixel cung.
 *    3. Gate saturation & value (smoothstep): loai hat lo lung xam/trang va
 *       wetsuit den co hue ngau nhien — chi pixel "giong da" moi duoc khoa.
 *  Ap dung: chinh Layer 3 chi tac dong len NGHICH DAO mask —
 *    final = mix(adjusted, input, mask * strength).
 *  maskView = 1: xuat mask grayscale de nguoi dung soi vung duoc bao ve.
 * ============================================================================
 */
layout(set = 0, binding = 0) uniform sampler2D uGradedLut;   // midB (sau Layer 2)

layout(set = 0, binding = 1, std140) uniform Layer3Ubo {
    vec4 hsl[7];        // (hueDeg, sat, luma, 0) — R(0) O(30) Y(60) G(120) C(180) B(240) M(300)
    vec4 misc;          // (globalSat, skinProtect, layerOn, 0)
    vec4 shadowTint;    // (r, g, b, 0)
    vec4 skinMask;      // S2: (targetHueDeg, toleranceDeg, featherDeg, strength)
    vec4 skinMask2;     // S2: (enable, maskView, satGateLo, valGateLo)
} u;

layout(location = 0) in  vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

const float HSL_CENTER[7] = float[7](0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 300.0);
const float HSL_WIDTH = 35.0;

float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

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

/*
 * S2 — trong so mask tone da cua MOT pixel (0 = nen nuoc, 1 = da duoc khoa).
 * hsvIn: HSV cua mau dau vao pass (truoc chinh Layer 3).
 */
float skinLockWeight(vec3 hsvIn) {
    float hueDeg = hsvIn.x * 360.0;
    // Khoang cach hue TRON (0 va 360 la mot diem)
    float d = abs(hueDeg - u.skinMask.x);
    d = min(d, 360.0 - d);
    // Loi tron ven + vung chuyen feather muot (spec: smoothstep, khong ria cung)
    float w = 1.0 - smoothstep(u.skinMask.y, u.skinMask.y + u.skinMask.z, d);
    // Gate saturation: da co sat vua phai; hat trang/xam (sat ~ 0) khong duoc khoa
    w *= smoothstep(u.skinMask2.z, u.skinMask2.z + 0.10, hsvIn.y);
    // Gate value: vung gan den (wetsuit, bong toi) hue nhieu — loai
    w *= smoothstep(u.skinMask2.w, u.skinMask2.w + 0.08, hsvIn.z);
    return clamp(w, 0.0, 1.0);
}

void main() {
    vec3 cIn = texture(uGradedLut, vTexCoord).rgb;   // mau dau vao pass (goc cho mask)
    vec3 c = cIn;
    if (u.misc.z < 0.5) {                     // eye-toggle Layer 3: bypass
        fragColor = vec4(c, 1.0);
        return;
    }

    // S2 — mask tinh TRUOC moi chinh sua, tu mau dau vao
    vec3 hsvIn = rgb2hsv(cIn);
    float lockW = (u.skinMask2.x > 0.5) ? skinLockWeight(hsvIn) * u.skinMask.w : 0.0;
    if (u.skinMask2.y > 0.5) {                // che do xem mask (debug)
        fragColor = vec4(vec3(lockW), 1.0);
        return;
    }

    vec3 hsv = hsvIn;
    float hueDeg = hsv.x * 360.0;
    bool skinBand = (hueDeg >= 15.0 && hueDeg <= 45.0);

    float hueShift = 0.0, satMul = 1.0, lumaAdd = 0.0;
    for (int i = 0; i < 7; i++) {
        float w = bandWeight(hueDeg, HSL_CENTER[i]);
        hueShift += w * u.hsl[i].x;
        satMul   *= 1.0 + w * u.hsl[i].y;
        lumaAdd  += w * u.hsl[i].z * 0.30;
    }
    // Skin Tone Protection: khoa hue-shift + gioi han sat trong dai da 15..45 do
    if (u.misc.y > 0.5 && skinBand) {
        hueShift = 0.0;
        satMul = clamp(satMul, 0.95, 1.08);
    }
    hsv.x = fract(hsv.x + hueShift / 360.0 + 1.0);
    hsv.y = clamp(hsv.y * satMul, 0.0, 1.0);
    hsv.z = clamp(hsv.z * (1.0 + lumaAdd), 0.0, 1.0);
    c = hsv2rgb(hsv);

    // Global Saturation
    float Y = luma709(c);
    c = mix(vec3(Y), c, 1.0 + u.misc.x);

    // Shadow tint (color wheel vung toi — teal cua Indo Moody)
    c += u.shadowTint.rgb * pow(1.0 - Y, 2.0);

    // S2 — SKIN LOCK: chinh Layer 3 chi ap len NGHICH DAO mask.
    // Pixel da (lockW ~ 1) tra ve mau dau vao pass; vung feather blend muot.
    c = mix(c, cIn, lockW);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
