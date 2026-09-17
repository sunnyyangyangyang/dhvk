package dev.dhvk.mesh;

import java.util.HashMap;

/**
 * L0 tile 表的最小 map 形态（任务1 生产 / 单测同形；任务2 升注册表：
 * 分级 + LRU 驻留 + generation，规格 §4.2 触发规则的宿主）。
 */
public final class MapTileTable implements TileTable {

    private final HashMap<CellKey, BlockTileView> tiles = new HashMap<>();

    @Override
    public BlockTileView get(int ix, int iy, int iz) {
        return tiles.get(new CellKey(ix, iy, iz, 0));
    }

    @Override
    public void put(CellKey key, BlockTileView tile) {
        tiles.put(key, tile);
    }
}
