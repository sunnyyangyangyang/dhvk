#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;

// S1 任务 2: 堆源 phantom 绑定 —— 描述符 payload = 墙 VBO/IBO 的设备地址区间,
// 驱动经 descriptor heap(vkCmdBindResourceHeapEXT + 命令级 mapping)取地址,
// 顶点输入状态机与 in 声明不变(笔记 §1 裁决)。shader 解引用 VBO/IBO 头:
// 一是把两个 OpVariable 钉在 SPIR-V 里(rebind 派生 entry 的前提),
// 二是堆源判别式(见 main)。
layout(std140) uniform VBO { vec4 pad; } u_vbo_phantom;
layout(std140) uniform IBO { vec4 pad; } u_ibo_phantom;

out vec4 vertexColor;
out float vertexSphericalDistance;
out float vertexCylindricalDistance;

// S0:世界坐标顶点(4000 块外的固定远墙)。
// ModelViewMat/ProjMat 与地形同一套默认 uniform,
// 雾距按球形/圆柱双通道输出,交给 fsh 走官方 apply_fog。
void main() {
    // 堆源判别式: 健康时 probe = VBO 头 x(-400.0, 墙 A 首顶点) + IBO 头 x(0.0) → 位移 0,
    // 与 S0 像素级一致; 堆失效(描述符读 0)→ 墙 A 瞬移到世界原点, 肉眼不可误判。
    float probe = u_vbo_phantom.pad.x + u_ibo_phantom.pad.x;
    vec3 pos = Position + vec3(probe + 400.0, 0.0, 0.0);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexSphericalDistance = fog_spherical_distance(pos);
    vertexCylindricalDistance = fog_cylindrical_distance(pos);
    vertexColor = Color;
}
