#version 330

// run25 探针: 直通顶点色(不引 fog.glsl → FS 接口为空, 与 run22-24 已实证
// "FS 挂全量映射 VVL 零点名" 的形态同构)。
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    fragColor = vertexColor;
}
