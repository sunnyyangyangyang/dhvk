package dev.dhvk.mesh;

/**
 * L0 tile 表（tile 坐标 = 世界坐标 / 32）。
 *
 * <p>任务1 = 最小 map 形态（生产 / 测试同形）；任务2 升注册表
 * （分级 + LRU 驻留 + generation，规格 §4.2 触发规则的宿主）。
 */
public interface TileTable {

    /** tile 坐标 (ix, iy, iz) 处的 tile；null = 尚未构建（= 缺失，该侧按空气，笔记 §10.1）。 */
    BlockTileView get(int ix, int iy, int iz);

    /** 登记一个已构建的 tile（构建器建完即登记，使后续邻 tile 可采样到它）。 */
    void put(CellKey key, BlockTileView tile);
}
