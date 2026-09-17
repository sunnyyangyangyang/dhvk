package dev.dhvk.mesh;

/**
 * Cell 坐标 (ix, iy, iz, level)。唯一坐标类型（自 S1 单体内部结构抽出）。
 *
 * <p>level：0 = L0（32 blocks）/ 1 = L1（128）/ 2 = L2（512）/ 3 = L3（2048）。
 * 边长 = 32 << (2*level)；原点 = 坐标 × 边长（世界 block 坐标，cell 对齐）。
 */
public record CellKey(int ix, int iy, int iz, int level) {

    /** Cell 边长（blocks）。L0=32 L1=128 L2=512 L3=2048。 */
    public long sideBlocks() {
        return 32L << (2L * level);
    }

    /** Cell 原点 X（世界 block 坐标）。 */
    public long originX() {
        return (long) ix * sideBlocks();
    }

    /** Cell 原点 Y（世界 block 坐标）。 */
    public long originY() {
        return (long) iy * sideBlocks();
    }

    /** Cell 原点 Z（世界 block 坐标）。 */
    public long originZ() {
        return (long) iz * sideBlocks();
    }
}
