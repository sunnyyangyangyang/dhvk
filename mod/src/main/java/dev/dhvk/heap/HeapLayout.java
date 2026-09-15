package dev.dhvk.heap;

/**
 * 描述符表槽位算术(纯 Java, 无 native): per-cell 子区按 S4 最坏预算预摊(R-c),
 * 堆表不重建不搬家。slot = 一个 buffer 描述符槽(VBO 或 IBO 各占一个)。
 *
 * <p><b>run24 表宽修正</b>: 槽宽必须 = max(驱动 bufferDescriptorAlignment,
 * 驱动 bufferDescriptorSize) 再对齐 —— 描述符本体是 bufferDescriptorSize(5090 实测 16B)
 * 宽, 按 8B 对齐值铺槽会让相邻 16B 描述符重叠(size 字段互相覆盖, offset≥8 的槽从未被
 * run19b 验证过: 当时唯一实证读取 = VBO@offset 0)。调用方经 slotStride(align, descSize)
 * 计算槽宽后传入。
 */
public final class HeapLayout {

    /** S1: 每 cell 2 槽 = VBO+IBO; S2 并入 UBO 时扩。 */
    public static final int S1_SLOTS_PER_CELL = 2;

    private HeapLayout() {
    }

    /** cell 的第 slotIndex 个槽位在堆内的字节偏移(槽宽见类注释)。 */
    public static long slotOffset(long cell, int slotIndex, long slotStride) {
        return (cell * (long) S1_SLOTS_PER_CELL + slotIndex) * slotStride;
    }

    /** 堆表大小 = cell 数 × 每 cell 槽数 × 槽宽(run24: 槽宽 ≥ 描述符实际宽度)。 */
    public static long totalBytes(long cellCapacity, int slotsPerCell, long slotStride) {
        return cellCapacity * slotsPerCell * slotStride;
    }

    /** run24: 槽宽 = max(对齐, 描述符实际宽度) 再按对齐取整 —— 16B 描述符绝不允许挤进 8B 槽。 */
    public static long slotStride(long slotAlignment, long descSize) {
        long s = Math.max(slotAlignment, descSize);
        return (s + slotAlignment - 1L) / slotAlignment * slotAlignment;
    }
}
