package dev.dhvk;

import java.nio.ByteBuffer;

/**
 * S1 任务 4: 远端体素墙的 per-frame CPU 合成器("几何搬运工"的搬运本体)。
 *
 * <p>每帧产出一面 32×32 cell 的行波墙(高度场扫过 + 色相随波相位流动)加一面静态
 * 远雾墙 B。顶点格式与任务 2 完全同形(vec3 pos + vec4 color = 28B, little-endian),
 * 因此四条堆管线(含任务 3 的 2×2 FSR 节点)的顶点输入状态与 shader 一字不改。
 *
 * <p>产物每帧写入官方 {@code VulkanTransientMemory} 瞬态 ring 的两个 slice
 * (VBO/IBO 各一 —— 官方 TransientGpuBuffer 不可子切片, 双 slice 是正身姿势),
 * 堆表 VBO/IBO 两槽在绘制前重写为本帧 slice 的 BDA(见 FarTerrainRenderer)。
 *
 * <p>S2v2 步骤 15(高度场退役): 帧路径默认只走贪心带; 本环降级为机制验收工具 ——
 * {@code DHVK_SYNTRING=1} 换回本环, 独立再验证瞬态环上传/绘制/堆表机制
 * (规格 §0: 与高度场退役无关, 墙 A/B + 探针板几何照旧)。
 */
public final class VoxelWallSynthesizer {

    /** 行波墙网格 cell 数(边): 32×32 块(run44 起放大 —— S1 目验验收需要雾带里无歧义的巨墙)。 */
    public static final int GRID = 32;
    /** 网格顶点数 (17×17) + 远雾墙 B 四角。 */
    public static final int VERT_COUNT = (GRID + 1) * (GRID + 1) + 4;
    /** 每顶点 28B (3 float + 4 byte)。 */
    public static final int VBO_BYTES = VERT_COUNT * 28;
    /** (32×32 行波 quad + 1 远雾墙 B quad) × 6 索引。 */
    public static final int TOTAL_INDICES = (GRID * GRID + 1) * 6;
    public static final int IBO_BYTES = TOTAL_INDICES * 2;

    private VoxelWallSynthesizer() {
    }

    /**
     * 合成一帧远端几何。
     *
     * @param phase 行波相位(弧度, 调用方按帧号推进)
     * @param aX    行波墙的 X(块)
     * @param bX    远雾墙 B 的 X(块)
     * @param y0    墙底 Y(块)
     * @param y1    墙顶 Y(块)
     * @param halfZ 墙 Z 半宽(块)
     * @param vbo   输出 VBO(容量 ≥ {@link #VBO_BYTES})
     * @param ibo   输出 IBO(容量 ≥ {@link #IBO_BYTES})
     */
    public static void synthesize(long frame, float phase, float aX, float bX,
            float y0, float y1, float halfZ, ByteBuffer vbo, ByteBuffer ibo) {
        int[][] grid = new int[GRID + 1][GRID + 1];
        int v = 0;
        for (int gy = 0; gy <= GRID; gy++) {
            for (int gz = 0; gz <= GRID; gz++) {
                grid[gy][gz] = v++;
                // cell 均分墙宽/墙高(放大后不再 1:1, 波相位仍按块坐标计算, 波长不变)
                float z = -halfZ + 2.0F * halfZ * gz / GRID;
                float yBase = y0 + (y1 - y0) * gy / GRID;
                float w = (float) Math.sin(0.55 * z + 0.35 * yBase + phase);
                float y = yBase + 1.5f * w;
                float cr = 0.5f + 0.5f * (float) Math.sin(w * 2.0);
                float cg = 0.5f + 0.5f * (float) Math.sin(w * 2.0 + 2.1);
                float cb = 0.5f + 0.5f * (float) Math.sin(w * 2.0 + 4.2);
                vbo.putFloat(aX);
                vbo.putFloat(y);
                vbo.putFloat(z);
                vbo.put((byte) (cr * 255.0f));
                vbo.put((byte) (cg * 255.0f));
                vbo.put((byte) (cb * 255.0f));
                vbo.put((byte) 255);
            }
        }
        // 远雾墙 B: 静态四角(任务 2 同款四色), 供官方雾吞没的远带对照
        float[][] bColors = {{255, 0, 255}, {0, 255, 255}, {255, 255, 0}, {0, 255, 0}};
        float[][] bPos = {{bX, y0, -halfZ}, {bX, y1, -halfZ}, {bX, y1, halfZ}, {bX, y0, halfZ}};
        for (int i = 0; i < 4; i++) {
            vbo.putFloat(bPos[i][0]);
            vbo.putFloat(bPos[i][1]);
            vbo.putFloat(bPos[i][2]);
            vbo.put((byte) bColors[i][0]);
            vbo.put((byte) bColors[i][1]);
            vbo.put((byte) bColors[i][2]);
            vbo.put((byte) 255);
        }
        // 行波墙 32×32 quad
        for (int gy = 0; gy < GRID; gy++) {
            for (int gz = 0; gz < GRID; gz++) {
                int a = grid[gy][gz];
                int b = grid[gy][gz + 1];
                int c = grid[gy + 1][gz + 1];
                int d = grid[gy + 1][gz];
                ibo.putShort((short) a);
                ibo.putShort((short) b);
                ibo.putShort((short) c);
                ibo.putShort((short) a);
                ibo.putShort((short) c);
                ibo.putShort((short) d);
            }
        }
        // 远雾墙 B quad
        int base = (GRID + 1) * (GRID + 1);
        ibo.putShort((short) base);
        ibo.putShort((short) (base + 1));
        ibo.putShort((short) (base + 2));
        ibo.putShort((short) base);
        ibo.putShort((short) (base + 2));
        ibo.putShort((short) (base + 3));
    }
}
