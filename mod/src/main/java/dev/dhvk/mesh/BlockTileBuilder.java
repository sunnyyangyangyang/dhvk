package dev.dhvk.mesh;

/**
 * chunk 方块数据 → 32³ block tile（与 GPU 上传同源，红线 R-b；任务1）。
 *
 * <p>生产路径：BlockSource = 包 Level 的官方数据链（block state + 精灵解析 +
 * BiomeColors 染色，笔记 §10.6）；构建完成的 tile 立即登记进 TileTable，
 * 使后续邻 tile 构建 / 提取时可采样到它（margin 裁决，笔记 §10.1）。
 */
public final class BlockTileBuilder {

    private BlockTileBuilder() {
    }

    /**
     * 构建 tile 并登记进表。
     *
     * @param source 方块数据源（世界 block 坐标）
     * @param table  tile 表（建完即 put；L0 坐标 = 原点 / 32）
     * @param ox     tile 世界原点 X（block，32 的倍数）
     * @param oy     tile 世界原点 Y（block，32 的倍数）
     * @param oz     tile 世界原点 Z（block，32 的倍数）
     * @return 构建好的 tile（occupancy = 非 air 且可见；hasData = 区域内任一位置已产生）
     */
    public static BlockTile build(BlockSource source, TileTable table,
            long ox, long oy, long oz) {
        int n = BlockTile.SIDES * BlockTile.SIDES * BlockTile.SIDES;
        boolean[] solid = new boolean[n];
        short[] sprite = new short[n];
        int[] tint = new int[n];
        boolean data = false;
        for (int y = 0; y < BlockTile.SIDES; y++) {
            for (int z = 0; z < BlockTile.SIDES; z++) {
                for (int x = 0; x < BlockTile.SIDES; x++) {
                    int wx = (int) ox + x;
                    int wy = (int) oy + y;
                    int wz = (int) oz + z;
                    int i = BlockTile.idx(x, y, z);
                    boolean air = source.isAir(wx, wy, wz);
                    solid[i] = !air;
                    if (!air) {
                        sprite[i] = (short) source.spriteId(wx, wy, wz);
                        tint[i] = source.tintRgb(wx, wy, wz);
                    }
                    if (source.hasData(wx, wy, wz)) {
                        data = true;
                    }
                }
            }
        }
        BlockTile tile = new BlockTile(table, ox, oy, oz, solid, sprite, tint, data);
        table.put(new CellKey((int) (ox / BlockTile.SIDES),
                (int) (oy / BlockTile.SIDES), (int) (oz / BlockTile.SIDES), 0), tile);
        return tile;
    }
}
