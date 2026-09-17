#version 330

#moj_import <minecraft:fog.glsl>

in vec4 vertexColor;
in float vertexSphericalDistance;
in float vertexCylindricalDistance;

out vec4 fragColor;

// 移植态: 无光照远几何 —— 顶点色 + 官方球形/圆柱双通道距离雾 (canary 自报退役)。
void main() {
    fragColor = apply_fog(
        vertexColor,
        vertexSphericalDistance,
        vertexCylindricalDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}
