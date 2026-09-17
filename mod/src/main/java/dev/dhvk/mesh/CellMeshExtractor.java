package dev.dhvk.mesh;

/**
 * Mesher 接口（红线 R-d：执行器可换、DAG 不变）。
 *
 * <p>v2 实现 = {@link CpuGreedyMeshExtractor}（CPU 二进制贪心，规格 §6）；
 * 第三实现位预留 S4（GPU compute / shader enqueue 进 DAG）——调用方
 * （注册表 / DAG 调度）一行不改。
 */
public interface CellMeshExtractor {

    /**
     * 提取 tile 所辖 cell 的表面几何，写入堆子区。
     *
     * <p>实现约束：同步（CPU 侧）；只写 {@code out}，不改 {@code tile}；
     * 输出字节布局 = 规格 §5.1 SoA（任务1 spike 期用 28B 子集：pos f32×3 +
     * color f32×4 + IBO 顺序 u32 三角汤，复用 S1 drawIndexed 路径）。
     *
     * @param key  cell 坐标（含 level）
     * @param tile 32³ block tile 读视图（与 GPU 上传同源，R-b）
     * @param out  堆子区写窗口（R-c 预算，SoA 区域偏移表）
     */
    void extract(CellKey key, BlockTileView tile, HeapSubregion out);
}
