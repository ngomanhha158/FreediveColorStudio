#version 450
/*
 * ============================================================================
 *  FABLE TASK 1.4 — LAYER 2 LUT ENGINE · Vulkan GLSL
 *  Noi suy TU DIEN (Tetrahedral Interpolation) tren LUT 3D .cube 33^3/65^3.
 *  Trilinear gay banding o vung gradient nuoc 10-bit — tetrahedral chia moi
 *  cell thanh 6 tu dien theo thu tu kenh (R>G>B...), noi suy trong tu dien
 *  chua diem can tinh -> chuyen mau muot, khong banding.
 * ----------------------------------------------------------------------------
 *  Input:  ket qua Layer 1 (color_space.frag, outputLinear=0 -> Rec.709)
 *  Sampler: VK_FILTER_NEAREST + CLAMP_TO_EDGE (shader tu noi suy texelFetch)
 *  Intensity (LUT Mix 0..1) truyen qua push constants.
 * ============================================================================
 */
layout(set = 0, binding = 0) uniform sampler2D uInputTex;   // output cua Layer 1
layout(set = 0, binding = 1) uniform sampler3D uLutTex;     // LUT .cube da upload

layout(location = 0) in  vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform LutPC {
    float intensity;    // 0..1 — LUT Mix slider (0 = tat, 1 = 100%)
    float lutSize;      // 33.0 hoac 65.0
    vec2  _pad0;
} pc;

vec3 lutFetch(ivec3 p, int N) {
    p = clamp(p, ivec3(0), ivec3(N - 1));
    return texelFetch(uLutTex, p, 0).rgb;
}

/* Thuat toan noi suy tu dien chuan — 6 nhanh theo thu tu lon-nho cua f.rgb */
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

void main() {
    vec3 src = texture(uInputTex, vTexCoord).rgb;
    if (pc.intensity <= 0.001) { fragColor = vec4(src, 1.0); return; }
    vec3 graded = sampleLutTetrahedral(src, int(pc.lutSize));
    fragColor = vec4(mix(src, graded, clamp(pc.intensity, 0.0, 1.0)), 1.0);
}
