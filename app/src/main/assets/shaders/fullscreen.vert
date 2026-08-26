#version 450
/* Tam giac phu man hinh — khong can vertex buffer (gl_VertexIndex trick) */
layout(location = 0) out vec2 vTexCoord;
void main() {
    vTexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(vTexCoord * 2.0 - 1.0, 0.0, 1.0);
}
