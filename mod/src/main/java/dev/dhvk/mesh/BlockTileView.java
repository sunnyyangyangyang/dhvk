package dev.dhvk.mesh;

/**
 * 32³ block tile 读视图（CPU 镜像，与 GPU 上传同源，红线 R-b）。
 *
 * <p>occupancy 语义 = “非 air 且可见”（opaque + cutout，规格 §6 MC 化）；
 * spriteID / tint 为逐体素另查数据。margin 语义按笔记 §10.1 裁决：
 * tile 32³ 本体，面剔除越界时由提取器按需查邻 tile 邻边，
 * 邻 tile 缺失（hasData()=false）该侧按空气。
 */
public interface BlockTileView {

    /** tile 边长（blocks）= 32。 */
    int SIDES = 32;

    /** 体素 (x, y, z)（0..31）是否实心（非 air 且可见）。 */
    boolean isSolid(int x, int y, int z);

    /** 体素 (x, y, z) 的精灵 ID（u16 范围；全局表索引，任务3）。 */
    int spriteId(int x, int y, int z);

    /** 体素 (x, y, z) 的 biome 染色（packed rgb8；BiomeColors 链，笔记 §10.6）。 */
    int tintRgb(int x, int y, int z);

    /** 该 tile 是否有真实数据；false = 从未产生（规格 §4.2 缺数据规则前置）。 */
    boolean hasData();

    /**
     * 邻采样（margin 裁决，笔记 §10.1）：邻 tile (dx, dy, dz) 的 tile 局部 (x, y, z)。
     * (dx, dy, dz) = (0, 0, 0) 时即本 tile 本体。邻 tile 缺失（null 或 hasData()=false）
     * 返回 false——“邻 tile 从未产生，该侧按空气”。
     */
    boolean isSolidNeighbor(int dx, int dy, int dz, int x, int y, int z);

    /** 邻采样：邻 tile 的精灵 ID（缺失 = 0）。 */
    int spriteIdNeighbor(int dx, int dy, int dz, int x, int y, int z);

    /** 邻采样：邻 tile 的染色（缺失 = 0）。 */
    int tintRgbNeighbor(int dx, int dy, int dz, int x, int y, int z);
}
