package dev.dhvk.mesh;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2v2 任务1（贪心 spike）Step 1~4：BlockTileBuilder 的 TDD 用例。
 * 图案 = 确定性内存 source（下半实心 / 上半空气），对拍手算值。
 */
final class BlockTileBuilderTest {

    /** 确定性内存 BlockSource：y < 16 实心（sprite=7, tint=0x305030），其余 air。 */
    static final class MemSource implements BlockSource {
        private final boolean produced;

        MemSource(boolean produced) {
            this.produced = produced;
        }

        @Override
        public int blockStateId(int x, int y, int z) {
            return isAir(x, y, z) ? 0 : 1;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return !produced || y >= 16;
        }

        @Override
        public int spriteId(int x, int y, int z) {
            return isAir(x, y, z) ? 0 : 7;
        }

        @Override
        public int tintRgb(int x, int y, int z) {
            return isAir(x, y, z) ? 0 : 0x305030;
        }

        @Override
        public boolean hasData(int x, int y, int z) {
            return produced;
        }
    }

    /** 最小 map 形态 TileTable（单测 / 任务1 生产同形）。 */
    static final class MapTable implements TileTable {
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

    @Test
    void testTileFromSolidPattern() {
        // 下半实心 / 上半空气 → isSolid 逐列正确；hasData=true；sprite/tint 按注入值。
        BlockTile tile = BlockTileBuilder.build(new MemSource(true), new MapTable(), 0, 0, 0);
        assertTrue(tile.hasData());
        assertTrue(tile.isSolid(0, 0, 0));
        assertTrue(tile.isSolid(31, 15, 31));
        assertFalse(tile.isSolid(0, 16, 0));
        assertFalse(tile.isSolid(31, 31, 31));
        assertEquals(7, tile.spriteId(5, 3, 5));
        assertEquals(0x305030, tile.tintRgb(5, 3, 5));
        assertEquals(0, tile.spriteId(5, 20, 5));
    }

    @Test
    void testTileMissing() {
        // 全 air 且从未产生 → hasData()=false（规格 §4.2 缺数据规则前置）。
        BlockTile tile = BlockTileBuilder.build(new MemSource(false), new MapTable(), 0, 0, 0);
        assertFalse(tile.hasData());
        assertFalse(tile.isSolid(0, 0, 0));
        assertEquals(0, tile.spriteId(0, 0, 0));
    }

    @Test
    void testNeighborSampling() {
        // 相邻两 tile：+x 缺失邻按空气（笔记 §10.1）；-x 邻可采到实心。
        TileTable table = new MapTable();
        BlockTile a = BlockTileBuilder.build(new MemSource(true), table, 0, 0, 0);
        BlockTile b = BlockTileBuilder.build(new MemSource(true), table, 32, 0, 0);
        // b 的 -x 邻 = a：b 局部 (31,0,0) 的 -x 面邻 = a 局部 (0,0,0) 实心
        assertTrue(b.isSolidNeighbor(-1, 0, 0, 0, 0, 0));
        assertEquals(7, b.spriteIdNeighbor(-1, 0, 0, 0, 0, 0));
        // b 的 +x 邻缺失 → 空气
        assertFalse(b.isSolidNeighbor(1, 0, 0, 0, 0, 0));
        assertEquals(0, b.spriteIdNeighbor(1, 0, 0, 0, 0, 0));
        // 本体采样 = (0,0,0) 邻
        assertTrue(a.isSolidNeighbor(0, 0, 0, 0, 0, 0));
        assertFalse(a.isSolidNeighbor(0, 0, 0, 0, 31, 0));
    }
}
