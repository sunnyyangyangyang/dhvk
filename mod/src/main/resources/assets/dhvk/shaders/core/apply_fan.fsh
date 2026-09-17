#version 330

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D uSourceColorTexture;
uniform sampler2D uSourceDepthTexture;

// S3 合成扇 (逻辑直抄 DH apply.frag): 离屏深度 == 1.0 = 该像素未画到 → discard
// (非反Z, 深度清 1.0); 否则贴离屏颜色 —— 只覆盖我们体素带真正画出的区域。
void main() {
    float fragmentDepth = texture(uSourceDepthTexture, texCoord).r;
    if (fragmentDepth >= 0.999) {
        discard;
    }
    fragColor = texture(uSourceColorTexture, texCoord);
}
