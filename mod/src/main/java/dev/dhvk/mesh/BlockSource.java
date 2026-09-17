package dev.dhvk.mesh;

/**
 * 最小方块数据源抽象（世界 block 坐标）。
 *
 * <p>生产实现 = 包 Level 的官方数据链（block state 查询 + Material.Baked 精灵
 * 解析 + BiomeColors 染色，笔记 §10.6）；单测实现 = 确定性内存图案。
 * 提取器 / tile 构建只依赖本接口，不碰官方类型（读码先决的解耦点）。
 */
public interface BlockSource {

    /** 方块 state 紧凑标识（实现自定义编号；0 = air 惯例）。 */
    int blockStateId(int x, int y, int z);

    /** 位置 (x, y, z)（世界 block）是否 air。 */
    boolean isAir(int x, int y, int z);

    /** 位置 (x, y, z) 的精灵 ID（全局表索引，任务3；air = 0）。 */
    int spriteId(int x, int y, int z);

    /** 位置 (x, y, z) 的 biome 染色（packed rgb8；air = 0）。 */
    int tintRgb(int x, int y, int z);

    /** 位置 (x, y, z) 是否有真实数据（chunk 已产生）；false = 从未产生（规格 §4.2 前置）。 */
    boolean hasData(int x, int y, int z);
}
