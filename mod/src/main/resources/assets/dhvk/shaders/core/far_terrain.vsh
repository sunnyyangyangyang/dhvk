#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out float vertexSphericalDistance;
out float vertexCylindricalDistance;

// S0:世界坐标顶点(4000 块外的固定远墙)。
// ModelViewMat/ProjMat 与地形同一套默认 uniform,
// 雾距按球形/圆柱双通道输出,交给 fsh 走官方 apply_fog。
void main() {
    vec3 pos = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexSphericalDistance = fog_spherical_distance(pos);
    vertexCylindricalDistance = fog_cylindrical_distance(pos);
    vertexColor = Color;
}
