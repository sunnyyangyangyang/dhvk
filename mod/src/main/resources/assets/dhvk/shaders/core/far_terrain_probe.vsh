#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

// run30 探针: 堆绑定与正式 shader 完全同构(phantom UBO 钉 OpVariable),
// 裁剪不依赖任何矩阵 —— 两面墙钉死在屏幕左/右、近景、管线关深度测试。
layout(std140) uniform VBO { vec4 pad; } u_vbo_phantom;
layout(std140) uniform IBO { vec4 pad; } u_ibo_phantom;

out vec4 vertexColor;

// run30 无歧义长度自报告(run29 哨兵全黑 → 既非活体也非烘焙 = 读到的描述符是零:
// 要么偏移基准错(去零区捞)、要么 payload 字节被驱动按别的位模式解码、
// 要么 UBO 地址对齐不满足 minUniformBufferOffsetAlignment 被驱动拒读、
// 要么 fetch 路径整体死)。长度版让"健康"恒亮(正交行长度恒 1, 与朝向无关),
// 与"零"在色度上彻底分开:
//   品红 (1,~0.5,1) = 三路全健康
//   纯蓝 (0,0,1)   = 仅自有对齐缓冲(VBO/IBO)健康 → 官方 ring/Proj 缓冲地址
//                    对齐(或该族)有问题 → 转 staging 方案
//   绿 (1,~0.5,0)  = 官方缓冲健康、自有缓冲零(镜像嫌疑)
//   全黑           = fetch 路径整体失效/偏移错位 → run31 payload·偏移扫描
void main() {
    float wallId = (Position.x < -1000.0) ? 1.0 : 0.0;
    float cx = mix(-0.55, 0.55, wallId) + Position.z * 0.05;
    float cy = (Position.y - 64.0) * 0.01;
    gl_Position = vec4(cx, cy, 0.0, 1.0);
    vertexColor = vec4(
        clamp(length(vec3(ModelViewMat[0].x, ModelViewMat[0].y, ModelViewMat[0].z)), 0.0, 1.0),
        clamp(length(vec3(ProjMat[0].x, ProjMat[0].y, ProjMat[0].z)) * 0.5, 0.0, 1.0),
        clamp((abs(u_vbo_phantom.pad.x) + abs(u_ibo_phantom.pad.x)) / 400.0, 0.0, 1.0),
        1.0);
}
