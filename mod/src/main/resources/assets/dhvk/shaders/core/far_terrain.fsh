#version 330

#moj_import <minecraft:fog.glsl>

in vec4 vertexColor;
in float vertexSphericalDistance;
in float vertexCylindricalDistance;
// run81: 主管线 canary 自报 —— 本管线自己读到的堆槽位移量(探针板只证探针管线的槽)
in float canary;

out vec4 fragColor;

// S0:无光照远几何 —— 顶点色 + 官方球形/圆柱双通道距离雾。
void main() {
    // canary 健康 = 堆槽读到本帧 slice 头(VBO头-400 + IBO头0 → 位移0); 越界 = 主管线
    // 幻影槽指向非本帧 slice → 几何被整体平移(可能遁入雾中), 染青自报
    float healthy = (canary > -401.0 && canary < -399.0) ? 1.0 : 0.0;
    vec4 base = mix(vec4(0.0, 1.0, 1.0, 1.0), vertexColor, healthy);
    fragColor = apply_fog(
        base,
        vertexSphericalDistance,
        vertexCylindricalDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
