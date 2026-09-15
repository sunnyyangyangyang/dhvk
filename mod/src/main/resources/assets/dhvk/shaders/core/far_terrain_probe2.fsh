#version 330

// run26: 直通顶点色, 接口空(不引 fog → FS 无资源, 全量静态映射对其无害, run22-25 实证)。
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    fragColor = vertexColor;
}
