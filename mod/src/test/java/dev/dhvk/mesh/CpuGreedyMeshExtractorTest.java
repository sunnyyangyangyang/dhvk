package dev.dhvk.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2v2 任务1（贪心 spike）Step 5：CpuGreedyMeshExtractor 的 TDD 用例（先失败）。
 *
 * <p>ground truth = 手算小图案 + 参照仓库算法对拍（笔记 §10.7）：mask 面剔除
 *（邻空气才露面，六方向全出面 R-f）+ 面平面内贪心合并（仅同 sprite + 同 tint）
 * + 邻 tile 缺失按空气（笔记 §10.1 / 规格 §4.2）。
 *
 * <p>四边形坐标约定（与实现对拍）：(x,y,z) = 自该角沿 uAxis/vAxis 方向增长
 * w/h 的起点角；w/h = 块数（含体素边界 +1）。轴码：1=+x 2=+y 3=+z，负号为反方向。
 */
final class CpuGreedyMeshExtractorTest {

    /** 确定性内存 BlockSource：实心图案经三参谓词注入，sprite/tint 可逐位计算。 */
    static final class PatternSource implements BlockSource {
        private final boolean produced;
        private final TriBool solid;
        private final TriInt sprite;
        private final TriInt tint;

        PatternSource(boolean produced, TriBool solid, TriInt sprite, TriInt tint) {
            this.produced = produced;
            this.solid = solid;
            this.sprite = sprite;
            this.tint = tint;
        }

        @Override
        public int blockStateId(int x, int y, int z) {
            return isAir(x, y, z) ? 0 : 1;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return !produced || !solid.test(x, y, z);
        }

        @Override
        public int spriteId(int x, int y, int z) {
            return isAir(x, y, z) ? 0 : sprite.apply(x, y, z);
        }

        @Override
        public int tintRgb(int x, int y, int z) {
            return isAir(x, y, z) ? 0 : tint.apply(x, y, z);
        }

        @Override
        public boolean hasData(int x, int y, int z) {
            return produced;
        }
    }

    @FunctionalInterface
    interface TriBool {
        boolean test(int x, int y, int z);
    }

    @FunctionalInterface
    interface TriInt {
        int apply(int x, int y, int z);
    }

    /** 最小 map 形态 TileTable（与 BlockTileBuilderTest / 任务1 生产同形）。 */
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

    static CpuGreedyMeshExtractor.Quad quad(int x, int y, int z, int w, int h,
            int uAxis, int vAxis, int sprite, int tint) {
        return new CpuGreedyMeshExtractor.Quad(x, y, z, w, h, uAxis, vAxis, sprite, tint);
    }

    /** 四边形四角的 x 区间（v 轴只取 y/z，x 区间只受 u 轴影响）。 */
    static int[] xRange(CpuGreedyMeshExtractor.Quad q) {
        if (q.uAxis() == CpuGreedyMeshExtractor.AX) {
            return new int[] { q.x(), q.x() + q.w() };
        }
        if (q.uAxis() == -CpuGreedyMeshExtractor.AX) {
            return new int[] { q.x() - q.w(), q.x() };
        }
        return new int[] { q.x(), q.x() };
    }

    @Test
    void testSingleColumn() {
        // 空气中 1×1×32 实心柱（x=16, z=16，全高）：露面 = 4 侧 + 顶 + 底
        // （R-f 禁单层 skyline；下方邻 tile 缺失按空气）= 6 四边形 / 12 三角形。
        TileTable table = new MapTable();
        BlockTile tile = BlockTileBuilder.build(new PatternSource(true,
                (x, y, z) -> x == 16 && z == 16,
                (x, y, z) -> 7,
                (x, y, z) -> 0x305030), table, 0, 0, 0);
        assertTrue(tile.hasData());
        List<CpuGreedyMeshExtractor.Quad> quads = new CpuGreedyMeshExtractor().quads(tile);
        assertEquals(List.of(
                quad(16, 32, 17, 1, 1, 1, -3, 7, 0x305030),    // +y 顶面
                quad(16, 0, 16, 1, 1, 1, 3, 7, 0x305030),      // -y 底面
                quad(17, 32, 16, 1, 32, 3, -2, 7, 0x305030),   // +x 侧面
                quad(16, 32, 17, 1, 32, -3, -2, 7, 0x305030),  // -x 侧面
                quad(16, 32, 17, 1, 32, 1, -2, 7, 0x305030),   // +z 侧面
                quad(17, 0, 16, 1, 32, -1, 2, 7, 0x305030)),   // -z 侧面
                quads);
        // SoA 28B 子集写出对拍：pos f32×3 + color f32×4（tint）+ IBO 顺序 u32 三角汤。
        ByteBuffer win = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        HeapSubregion sub = new HeapSubregion(0, 64, 0, 1024, 0, 0, 0, 0, 0, 2048, win);
        new CpuGreedyMeshExtractor().extract(new CellKey(0, 0, 0, 0), tile, sub);
        // 首顶点 = 顶面 p0 = (16, 32, 17)
        assertEquals(16f, win.getFloat(0), 1e-6f);
        assertEquals(32f, win.getFloat(4), 1e-6f);
        assertEquals(17f, win.getFloat(8), 1e-6f);
        // color = tint 0x305030（精灵基色 spike 期 = 白，直乘 tint）
        assertEquals(0x30 / 255f, win.getFloat(1024), 1e-5f);
        assertEquals(0x50 / 255f, win.getFloat(1024 + 4), 1e-5f);
        assertEquals(0x30 / 255f, win.getFloat(1024 + 8), 1e-5f);
        assertEquals(1f, win.getFloat(1024 + 12), 1e-6f);
        // 6 四边形 → 36 顶点；全部坐标在 cell [0,32] 内
        for (int v = 0; v < 36; v++) {
            for (int c = 0; c < 3; c++) {
                float p = win.getFloat(v * 12 + c);
                assertTrue(p >= 0f && p <= 32f, "vertex out of cell: v=" + v + " c=" + c + " p=" + p);
            }
        }
        // IBO 顺序 u32：0,1,2,...,35
        for (int i = 0; i < 36; i++) {
            assertEquals(i, win.getInt(2048 + i * 4));
        }
    }

    @Test
    void testGreedyMerge() {
        // 4×4×1 实心板（y=0）：4 侧 + 顶 + 底各合并成 1 四边形 = 6；
        // 不合并基准 = 顶/底 16×2 + 侧 4×4 = 48 → 合并应显著小于基准。
        TileTable table = new MapTable();
        BlockTile tile = BlockTileBuilder.build(new PatternSource(true,
                (x, y, z) -> y == 0 && x < 4 && z < 4,
                (x, y, z) -> 7,
                (x, y, z) -> 0x305030), table, 0, 0, 0);
        List<CpuGreedyMeshExtractor.Quad> quads = new CpuGreedyMeshExtractor().quads(tile);
        assertTrue(quads.size() < 48, "未合并: " + quads.size());
        assertEquals(6, quads.size());
        // 顶面 4×4 合并为一个四边形；UV 轴属性 = +x / -z（规格 §5.2 tiling 规则的轴约定）。
        CpuGreedyMeshExtractor.Quad top = quads.stream()
                .filter(q -> q.uAxis() == CpuGreedyMeshExtractor.AX
                        && q.vAxis() == -CpuGreedyMeshExtractor.AZ)
                .findFirst().orElseThrow();
        assertEquals(quad(0, 1, 4, 4, 4, 1, -3, 7, 0x305030), top);
    }

    @Test
    void testNoMergeDifferentSprite() {
        // 相邻两块（x∈[0,4) sprite 7 与 x∈[4,8) sprite 9 相接）：
        // 接缝被面剔除（邻实心）；顶面按类型劈成两个 4×4 四边形，不越界合并。
        TileTable table = new MapTable();
        BlockTile tile = BlockTileBuilder.build(new PatternSource(true,
                (x, y, z) -> y == 0 && x < 8 && z < 4,
                (x, y, z) -> x < 4 ? 7 : 9,
                (x, y, z) -> 0x305030), table, 0, 0, 0);
        List<CpuGreedyMeshExtractor.Quad> quads = new CpuGreedyMeshExtractor().quads(tile);
        // A 块 5 面（+x 被 B 剔除）+ B 块 5 面（-x 被 A 剔除）= 10
        assertEquals(10, quads.size());
        List<CpuGreedyMeshExtractor.Quad> tops = quads.stream()
                .filter(q -> q.uAxis() == CpuGreedyMeshExtractor.AX
                        && q.vAxis() == -CpuGreedyMeshExtractor.AZ)
                .toList();
        assertEquals(2, tops.size());
        assertEquals(quad(0, 1, 4, 4, 4, 1, -3, 7, 0x305030), tops.get(0));
        assertEquals(quad(4, 1, 4, 4, 4, 1, -3, 9, 0x305030), tops.get(1));
        for (CpuGreedyMeshExtractor.Quad q : quads) {
            assertTrue(q.w() <= 4, "跨类型合并: " + q);
        }
    }

    @Test
    void testMissingDataAir() {
        // x<16 半从未产生（缺数据按空气，规格 §4.2）；x≥16 半下 16 层实心：
        // 数据缺口 x=16 边界必须露 -x 面；几何不得渗入缺失半区。
        TileTable table = new MapTable();
        BlockTile tile = BlockTileBuilder.build(new PatternSource(true,
                (x, y, z) -> x >= 16 && y < 16,
                (x, y, z) -> 7,
                (x, y, z) -> 0x305030), table, 0, 0, 0);
        assertTrue(tile.hasData());
        List<CpuGreedyMeshExtractor.Quad> quads = new CpuGreedyMeshExtractor().quads(tile);
        assertEquals(6, quads.size());
        // 缺口边界 -x 面（面3: u=-z v=-y）存在且位于 x=16 平面
        boolean boundaryFace = quads.stream()
                .anyMatch(q -> q.uAxis() == -CpuGreedyMeshExtractor.AZ
                        && q.vAxis() == -CpuGreedyMeshExtractor.AY && q.x() == 16);
        assertTrue(boundaryFace, "缺口边界面缺失: " + quads);
        for (CpuGreedyMeshExtractor.Quad q : quads) {
            int[] r = xRange(q);
            assertTrue(r[0] >= 16 && r[1] <= 32, "几何渗入缺失半区: " + q);
        }
    }
}
