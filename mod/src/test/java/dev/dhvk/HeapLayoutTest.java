package dev.dhvk;

import dev.dhvk.heap.HeapLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapLayoutTest {
    @Test
    void slotOffsetsAreAlignedAndDisjoint() {
        long align = 64; // 假设 5090 的 bufferDescriptorAlignment, 单测用假值即可验证算术
        assertEquals(0, HeapLayout.slotOffset(0, 0, align)); // cell 0 的 VBO 槽 = 堆头
        assertEquals(align, HeapLayout.slotOffset(0, 1, align)); // cell 0 的 IBO 槽 = 后一个对齐位
        long a = HeapLayout.slotOffset(1, 0, align);
        long b = HeapLayout.slotOffset(1, 1, align);
        assertTrue(b > a && (b % align) == 0); // 对齐且互不相交
        // 跨 cell: cell 1 的首槽恰在 cell 0 的整表之后(无重叠)
        assertEquals(HeapLayout.totalBytes(1, 2, align), a);
    }

    @Test
    void totalBytesCoversAllCellsAndSlots() {
        long total = HeapLayout.totalBytes(8192, 2, 64);
        assertEquals(8192 * 2 * 64, total); // 无预留区时的精确值(S1: reservedRange=0, 见笔记 §6)
    }

    @Test
    void slotStrideNeverBelowDescriptorSize() {
        assertEquals(16, HeapLayout.slotStride(8, 16)); // 5090 实测: align=8 descSize=16 → 16B 槽
        assertEquals(64, HeapLayout.slotStride(64, 16)); // 对齐更大时从对齐
        assertEquals(8, HeapLayout.slotStride(8, 8));
        assertEquals(32, HeapLayout.slotStride(16, 24)); // 取整到对齐边界
    }
}
