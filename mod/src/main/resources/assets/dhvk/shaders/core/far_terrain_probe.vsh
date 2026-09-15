#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;

// run34 哨兵横扫探针: 六表槽全部指向哨兵 buffer 的六个分片(offset 64*i, 头 float = i+1,
// 矩阵分片填近单位阵 / 雾分片 FogColor.x = 5 且环境雾 start=大值 end=0(alpha=0 不染白),
// 主墙变换保持正常)。六通道各读一片头 /6:
//   0.0      = 该分片死(描述符读零)
//   (i+1)/6  = 该分片活
// 槽 0..5 = VBO/IBO/Proj/DT/Fog/Globals; 双板按 wallId 分半:
//   板A(左) = (v0, v1, v2), 板B(右) = (v3, v4, v5);
//   六片全活时: 板A = (1/6, 2/6, 3/6), 板B = (4/6, 5/6, 1.0)。
// Fog/Globals 用官方匿名块(与主管线同 import, 成员裸引用), VBO/IBO 用幻影块钉 OpVariable。
layout(std140) uniform VBO { vec4 pad; } u_vbo_phantom;
layout(std140) uniform IBO { vec4 pad; } u_ibo_phantom;

out vec4 vertexColor;

void main() {
    float wallId = (Position.x < -1000.0) ? 1.0 : 0.0;
    float cx = mix(-0.55, 0.55, wallId) + Position.z * 0.05;
    float cy = (Position.y - 64.0) * 0.01;
    gl_Position = vec4(cx, cy, 0.0, 1.0);
    float v0 = clamp(abs(u_vbo_phantom.pad.x) / 6.0, 0.0, 1.0);
    float v1 = clamp(abs(u_ibo_phantom.pad.x) / 6.0, 0.0, 1.0);
    float v2 = clamp(abs(ProjMat[0].x) / 6.0, 0.0, 1.0);
    float v3 = clamp(abs(ModelViewMat[0].x) / 6.0, 0.0, 1.0);
    float v4 = clamp(abs(FogColor.x) / 6.0, 0.0, 1.0);
    float v5 = clamp(abs(float(CameraBlockPos.x)) / 6.0, 0.0, 1.0);
    vertexColor = wallId < 0.5
        ? vec4(v0, v1, v2, 1.0)
        : vec4(v3, v4, v5, 1.0);
}
