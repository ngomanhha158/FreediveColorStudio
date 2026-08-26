#version 450
/*
 * ============================================================================
 *  TASK 4 + E3 — COMPOSITE + CLARITY + WATERMARK · thay the blit.frag
 *  Dua anh cuoi (midC) len swapchain, kem:
 *  · CLARITY (unsharp mask tren LUMA): local mean 8 tap + tam, tang tuong
 *    phan cuc bo theo pc.clarity; chi doi LUMA nen khong sinh vien mau.
 *  · WATERMARK (E3 — spec 4.3.3): logo PNG alpha (uWatermark, binding 1)
 *    blend srcOver vao goc PHAI-DUOI theo rect pc.wm* (toa do UV, C++ tinh
 *    tu kich thuoc logo + le 3%). pc.wmOn < 0.5 -> bo qua hoan toan.
 *    Ap sau CUNG (sau clarity) nen co mat trong ca preview lan file xuat
 *    (renderExportFrame dung chung duong ve nay).
 * ============================================================================
 */
layout(set = 0, binding = 0) uniform sampler2D uGraded;      // midC
layout(set = 0, binding = 1) uniform sampler2D uWatermark;   // E3 — logo RGBA

layout(location = 0) in  vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform CompositePC {
    float clarity;     // -1..+1 (spec preset 5: +0.15)
    float texelW;      // 1.0 / width
    float texelH;      // 1.0 / height
    float wmOn;        // E3: >0.5 = ve watermark
    vec4  wmRect;      // E3: (x, y, w, h) UV — goc trai-tren cua logo
} pc;

float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec3 c = texture(uGraded, vTexCoord).rgb;

    // ---- CLARITY (early-skip khi ~0 nhung khong return — con watermark) ----
    if (abs(pc.clarity) >= 0.001) {
        vec2 t = vec2(pc.texelW, pc.texelH) * 2.0;
        float m = luma709(c);
        m += luma709(texture(uGraded, vTexCoord + vec2( t.x, 0.0)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2(-t.x, 0.0)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2(0.0,  t.y)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2(0.0, -t.y)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2( t.x,  t.y)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2(-t.x,  t.y)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2( t.x, -t.y)).rgb);
        m += luma709(texture(uGraded, vTexCoord + vec2(-t.x, -t.y)).rgb);
        m /= 9.0;
        float y = luma709(c);
        float detail = y - m;                          // thanh phan tuong phan cuc bo
        float yNew = clamp(y + pc.clarity * detail * 1.5, 0.0, 1.0);
        c *= (y > 1e-5) ? (yNew / y) : 1.0;            // scale giu huong mau
    }

    // ---- E3: WATERMARK goc phai-duoi (alpha srcOver, 85% opacity) ----
    if (pc.wmOn > 0.5) {
        vec2 uv = (vTexCoord - pc.wmRect.xy) / pc.wmRect.zw;
        if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)))) {
            vec4 wm = texture(uWatermark, uv);
            c = mix(c, wm.rgb, wm.a * 0.85);
        }
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
