#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out float vertexSphericalDistance;
out float vertexCylindricalDistance;

// 移植态 (run89+): 无幻影 VBO/IBO 块、无 canary —— 顶点/索引走真实缓冲绑定
// (setVertexBuffer/setIndexBuffer 整 GpuBuffer), UBO 走官方 bindDefaultUniforms,
// 几何 = 我们的体素带 (buildMeshBand 产物)。
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexSphericalDistance = fog_spherical_distance(Position);
    vertexCylindricalDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;
}
