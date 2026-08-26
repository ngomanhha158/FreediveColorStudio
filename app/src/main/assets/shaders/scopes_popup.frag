#version 450
/*
 * ============================================================================
 *  TASK 2.4 — SCOPES POPUP (DRAW) · ve Vectorscope / Waveform tu SSBO bins
 *  Duoc ve trong pass composite voi VIEWPORT = khung popup (vi tri/kich thuoc
 *  do ScopesPopupView.kt dieu khien qua JNI). Alpha blend theo smart opacity
 *  (40% khi playback, 100% khi cham slider).
 *  Vectorscope co graticule vong tron 75%/100% + DUONG CHUAN TONE DA (xap xi
 *  huong (Cb,Cr) ~ (-0.65, 0.76) — canh mau da tho lan giua nuoc xanh).
 * ============================================================================
 */
layout(location = 0) in  vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 1) readonly buffer ScopeBins {
    uint vecBins[256 * 256];
    uint wfBins [256 * 256];
} bins;

layout(push_constant) uniform ScopeDrawPC {
    float mode;      // 1.0 = Vectorscope, 2.0 = Waveform
    float opacity;   // 0..1 (smart opacity)
    float gain;      // do khuech dai trace (mac dinh ~0.05)
    float _pad;
} pc;

const vec3 TRACE  = vec3(0.35, 1.0, 0.55);   // xanh la scope co dien
const vec3 GRAT   = vec3(0.55, 0.58, 0.60);  // graticule xam
const vec3 SKIN   = vec3(1.0, 0.62, 0.40);   // duong tone da

float traceAt(uint count) { return 1.0 - exp(-float(count) * pc.gain); }

void main() {
    vec2 uv = vTexCoord;                 // 0..1 trong khung popup
    vec3 col = vec3(0.05, 0.06, 0.07);   // nen toi
    float alpha = pc.opacity;

    if (pc.mode < 1.5) {
        /* ---------------- VECTORSCOPE ---------------- */
        vec2 cc = uv * 2.0 - 1.0;                    // -1..1, tam giua
        float r = length(cc);
        if (r > 1.0) { fragColor = vec4(0.0); return; }   // ngoai hinh tron

        // trace
        ivec2 b = clamp(ivec2((uv) * 255.0 + 0.5), ivec2(0), ivec2(255));
        float t = traceAt(bins.vecBins[b.y * 256 + b.x]);
        col = mix(col, TRACE, t);

        // graticule: vong 75% va 100%, truc giua
        float ring = min(abs(r - 0.75), abs(r - 1.0));
        col = mix(col, GRAT, smoothstep(0.012, 0.0, ring) * 0.6);
        col = mix(col, GRAT, (smoothstep(0.006, 0.0, abs(cc.x)) +
                              smoothstep(0.006, 0.0, abs(cc.y))) * 0.25);

        // duong chuan tone da (xap xi): huong (Cb,Cr) = (-0.65, +0.76)
        vec2 skinDir = normalize(vec2(-0.65, 0.76));
        float along = dot(cc, skinDir);
        float perp  = abs(dot(cc, vec2(-skinDir.y, skinDir.x)));
        if (along > 0.0 && along < 0.95)
            col = mix(col, SKIN, smoothstep(0.008, 0.0, perp) * 0.85);

        alpha *= smoothstep(1.0, 0.98, r);            // vien tron muot
    } else {
        /* ---------------- LUMA WAVEFORM ---------------- */
        int cbin = clamp(int(uv.x * 255.0), 0, 255);
        int ybin = clamp(int((1.0 - uv.y) * 255.0), 0, 255);
        float t = traceAt(bins.wfBins[cbin * 256 + ybin]);
        col = mix(col, TRACE, t);

        // graticule 0/25/50/75/100 IRE — canh diem den (chong crush ran san ho)
        for (int i = 0; i <= 4; i++) {
            float gy = float(i) * 0.25;
            col = mix(col, GRAT, smoothstep(0.006, 0.0, abs((1.0 - uv.y) - gy)) * 0.5);
        }
    }
    fragColor = vec4(col, alpha);
}
