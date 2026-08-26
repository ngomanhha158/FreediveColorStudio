#version 300 es
// ============================================================================
//  Vertex shader dung chung cho moi GlShaderProgram cua Media3.
//  GlProgram nap thuoc tinh "aFramePosition" bang GlUtil.getNormalizedCoordinateBounds()
//  (4 dinh, TRIANGLE_STRIP, phu kin NDC) — toa do texture suy ra tu vi tri.
// ============================================================================
in vec4 aFramePosition;
out vec2 vTexSamplingCoord;

void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
