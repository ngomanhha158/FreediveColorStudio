#version 450
/*
 * ============================================================================
 *  TASK 1.3 + 2.1 — CST + LAYER 1 PRE-LUT DAY DU · Vulkan GLSL (PASS 1)
 *  D-Log M 10-bit -> Linear -> Rec.709 + WB/Tint, Exposure, Contrast,
 *  Shadows/Highlights, Red Channel Recovery, Anti-Green.
 *  Output: Rec.709 display-referred vao target RGBA16F (midA) — dau vao
 *  chuan cho LUT Grade Co o Pass 2.
 *  Push constants 48 byte — khop fdc::Layer1PushConstants (layer1_push_constants.h)
 * ============================================================================
 */
layout(set = 0, binding = 0) uniform sampler2D uVideoTex;  // frame D-Log M (YCbCr conversion)

layout(location = 0) in  vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform Layer1PC {
    float temperature;   // -1..+1 (+ = am/vang)
    float tint;          // -1..+1 (+ = magenta)
    float exposureEv;    // EV stop
    float contrast;      // -1..+1 (pivot 0.4353 Rec.709)
    float shadowsLift;   // -1..+1
    float highlights;    // -1..+1
    float redRecovery;   // 0..1
    float antiGreen;     // 0..1 (kieu LumaFusion)
    float magentaGuard;  // 1.0 = gioi han tint magenta vung toi
    float layerOn;       // eye-toggle Layer 1: 0.0 = bypass (chi CST)
    float _pad0;
    float _pad1;
} pc;

float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

/* XAP XI D-Log M -> Linear (PLACEHOLDER — thay hang so chinh thuc DJI) */
vec3 dlogMToLinear(vec3 c) {
    return pow(max(c - vec3(0.0625), vec3(0.0)) * (1.0 / 0.8737), vec3(1.0 / 0.45));
}
vec3 linearToRec709(vec3 c) {
    c = max(c, vec3(0.0));
    return mix(c * 4.5, 1.099 * pow(c, vec3(0.45)) - 0.099, step(0.018, c));
}

void main() {
    vec3 lin = dlogMToLinear(texture(uVideoTex, vTexCoord).rgb);

    if (pc.layerOn < 0.5) {                      // eye-toggle: bypass Layer 1
        fragColor = vec4(clamp(linearToRec709(lin), 0.0, 1.0), 1.0);
        return;
    }

    // 1. Exposure (linear)
    lin *= exp2(pc.exposureEv);

    // 2. White Balance & Tint (guard chong nhieu magenta vung toi)
    float tintEff = pc.tint;
    if (pc.magentaGuard > 0.5) tintEff *= smoothstep(0.0, 0.35, luma709(lin));
    lin.r *= 1.0 + 0.25 * pc.temperature;
    lin.b *= 1.0 - 0.25 * pc.temperature;
    lin.g *= 1.0 - 0.20 * tintEff;

    // 3. Anti-Green thich nghi, bao toan luma
    if (pc.antiGreen > 0.001) {
        float y0 = luma709(lin);
        lin.g -= pc.antiGreen * max(lin.g - max(lin.r, lin.b), 0.0);
        lin *= y0 / max(luma709(lin), 1e-5);
    }

    // 4. Red Channel Recovery
    float refR = dot(lin.gb, vec2(0.7, 0.3));
    lin.r += pc.redRecovery * 0.5 * max(refR - lin.r, 0.0);

    // 5. Rec.709 + Contrast (pivot trung tinh) + Shadows/Highlights
    vec3 c = linearToRec709(lin);
    c = mix(vec3(0.4353), c, 1.0 + pc.contrast);
    float y = luma709(c);
    c += pc.shadowsLift * 0.25 * pow(1.0 - y, 2.0);
    c += pc.highlights  * 0.25 * pow(y, 2.0);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
