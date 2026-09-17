#version 330

in vec3 Position;
in vec4 Color;

// S3 合成扇: NDC 全屏四角三角扇; 输出 UV 供片元采样离屏结果
out vec2 texCoord;

void main() {
    texCoord = Position.xy * 0.5 + 0.5;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
