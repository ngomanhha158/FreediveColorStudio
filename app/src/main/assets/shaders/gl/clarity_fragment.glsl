#version 300 es
// ============================================================================
//  FREEDIVE COLOR — PASS CLARITY (GL ES 3.0)
//  Port cua phan CLARITY trong composite.frag (Vulkan).
//  Unsharp mask tren LUMA: local mean 9 tap, chi doi do sang nen khong sinh
//  vien mau. Tach thanh effect rieng vi can lay mau lan can cua ANH DA GRADE.
//  (Watermark van do duong xuat file Vulkan dam nhiem — chua dua vao preview.)
// ============================================================================
precision highp float;

uniform sampler2D uTexSampler;
uniform float uClarity;   // -1..+1
uniform float uTexelW;    // 1.0 / width
uniform float uTexelH;    // 1.0 / height

in  vec2 vTexSamplingCoord;
out vec4 outColor;

float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec3 c = texture(uTexSampler, vTexSamplingCoord).rgb;

    if (abs(uClarity) >= 0.001) {
        vec2 t = vec2(uTexelW, uTexelH) * 2.0;
        float m = luma709(c);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2( t.x, 0.0)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2(-t.x, 0.0)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2(0.0,  t.y)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2(0.0, -t.y)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2( t.x,  t.y)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2(-t.x,  t.y)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2( t.x, -t.y)).rgb);
        m += luma709(texture(uTexSampler, vTexSamplingCoord + vec2(-t.x, -t.y)).rgb);
        m /= 9.0;
        float y = luma709(c);
        float detail = y - m;
        float yNew = clamp(y + uClarity * detail * 1.5, 0.0, 1.0);
        c *= (y > 1e-5) ? (yNew / y) : 1.0;
    }

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
