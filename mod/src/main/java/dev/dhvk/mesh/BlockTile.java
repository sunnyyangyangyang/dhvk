package dev.dhvk.mesh;

/**
 * 32³ block tile 本体（CPU 镜像，与 GPU 上传同源，红线 R-b）。
 *
 * <p>occupancy = “非 air 且可见”（opaque + cutout 均非 air，规格 §6）。
 * margin 语义（笔记 §10.1 裁决）：不存 33³——越界的邻采样（*Neighbor）由
 * 提取器按需查邻 tile 邻边；邻 tile 缺失（未构建 / 从未产生）该侧按空气。
 */
public final class BlockTile implements BlockTileView {

    private final TileTable table;
    private final int ix;
    private final int iy;
    private final int iz;
    private final long ox;
    private final long oy;
    private final long oz;
    private final boolean[] solid;
    private final short[] sprite;
    private final int[] tint;
    private final boolean data;

    // package-private：BlockTileBuilder（同包）构建。
    BlockTile(TileTable table, long ox, long oy, long oz,
            boolean[] solid, short[] sprite, int[] tint, boolean data) {
        this.table = table;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.ix = (int) (ox / SIDES);
        this.iy = (int) (oy / SIDES);
        this.iz = (int) (oz / SIDES);
        this.solid = solid;
        this.sprite = sprite;
        this.tint = tint;
        this.data = data;
    }

    /** 线性索引（x 最快）。 */
    static int idx(int x, int y, int z) {
        return (y * SIDES + z) * SIDES + x;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return solid[idx(x, y, z)];
    }

    @Override
    public int spriteId(int x, int y, int z) {
        return sprite[idx(x, y, z)] & 0xFFFF;
    }

    @Override
    public int tintRgb(int x, int y, int z) {
        return tint[idx(x, y, z)];
    }

    @Override
    public boolean hasData() {
        return data;
    }

    @Override
    public boolean isSolidNeighbor(int dx, int dy, int dz, int x, int y, int z) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return isSolid(x, y, z);
        }
        BlockTileView n = table.get(ix + dx, iy + dy, iz + dz);
        return n != null && n.hasData() && n.isSolid(x, y, z);
    }

    @Override
    public int spriteIdNeighbor(int dx, int dy, int dz, int x, int y, int z) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return spriteId(x, y, z);
        }
        BlockTileView n = table.get(ix + dx, iy + dy, iz + dz);
        return (n != null && n.hasData()) ? n.spriteId(x, y, z) : 0;
    }

    @Override
    public int tintRgbNeighbor(int dx, int dy, int dz, int x, int y, int z) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return tintRgb(x, y, z);
        }
        BlockTileView n = table.get(ix + dx, iy + dy, iz + dz);
        return (n != null && n.hasData()) ? n.tintRgb(x, y, z) : 0;
    }
}
