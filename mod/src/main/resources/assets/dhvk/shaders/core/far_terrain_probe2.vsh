#version 330

// run26 双半屏仲裁探针: 不引用任何资源(接口空, 与 run22-24/25 已实证"空接口+全量
// 静态映射 VVL 零点名"同构), 不依赖任何矩阵。每面半墙钉死半个屏幕:
//   墙 A(x>-1000) → 左半屏, 墙 B(x<-1000) → 右半屏, 近景 z=0, w=1 不裁剪。
// 配合两条管线(纯写入 DEFAULT vs (ZERO,ONE) 混合)分画左右, 一次运行裁决:
//   ① 绘制路径是否出图; ② 现用 (ZERO,ONE) 颜色目标状态是 replace 还是 dst-pass。
in vec3 Position;

out vec4 vertexColor;

void main() {
    float wallId = (Position.x < -1000.0) ? 1.0 : 0.0;
    float sy = (Position.y > 64.0) ? 1.0 : -1.0;
    float sz = (Position.z > 0.0) ? 1.0 : -1.0;
    // 墙 A: cx ∈ [-1, 0](左半屏); 墙 B: cx ∈ [0, 1](右半屏); cy ∈ [-0.5, 0.5]
    float cx = mix(-0.5, 0.5, wallId) + sz * 0.5;
    float cy = sy * 0.5;
    gl_Position = vec4(cx, cy, 0.0, 1.0);
    vertexColor = (wallId < 0.5) ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 1.0, 1.0, 1.0);
}
