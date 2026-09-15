package dev.dhvk.heap;

/**
 * 描述符表槽位算术(纯 Java, 无 native): per-cell 子区按 S4 最坏预算预摊(R-c),
 * 堆表不重建不搬家。slot = 一个 buffer 描述符槽(VBO 或 IBO 各占一个)。
 */
public final class HeapLayout {

    /** S1: 每 cell 2 槽 = VBO+IBO; S2 并入 UBO 时扩。 */
    public static final int S1_SLOTS_PER_CELL = 2;

    private HeapLayout() {
    }

    /** cell 的第 slotIndex 个槽位在堆内的字节偏移(按 slotAlignment 对齐)。 */
    public static long slotOffset(long cell, int slotIndex, long slotAlignment) {
        return (cell * (long) S1_SLOTS_PER_CELL + slotIndex) * slotAlignment;
    }

    /** 堆总大小 = cell 数 × 每 cell 槽数 × 槽对齐(S1 reservedRange=0, 笔记 §6 定稿)。 */
    public static long totalBytes(long cellCapacity, int slotsPerCell, long slotAlignment) {
        return cellCapacity * slotsPerCell * slotAlignment;
    }
}
