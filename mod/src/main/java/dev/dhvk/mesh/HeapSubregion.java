package dev.dhvk.mesh;

import java.nio.ByteBuffer;

/**
 * 堆子区字节视图（红线 R-c：预算预摊不搬家；布局 = 规格 §5.1 SoA，
 * GPU 端也能直接写——禁止 Java 对象 / List 中间表示）。
 *
 * <p>SoA 区域（相对 baseOffset 的字节偏移，按对齐）：
 * pos f32×3 / color f32×4（任务1 子集 = R-e v1 形）/ uvAbs f32×2 /
 * spriteID u16 / ao u8 / tint rgb8 / normal u8（任务3~4 启用）/
 * index u32（顺序索引三角汤：3i / 3i+1 / 3i+2，复用 S1 drawIndexed 路径，
 * 渲染器零改）。
 *
 * @param baseOffset   resource heap 内偏移（字节）
 * @param vertexCapacity 顶点容量（R-c 预算；任务1 spike 实测最坏 × 1.5 定死）
 * @param posOffset    SoA pos 区偏移
 * @param colorOffset  SoA color 区偏移
 * @param uvAbsOffset  SoA uvAbs 区偏移（任务3）
 * @param spriteOffset SoA spriteID 区偏移（任务3）
 * @param aoOffset     SoA ao 区偏移（任务4）
 * @param tintOffset   SoA tint 区偏移（任务3）
 * @param normalOffset SoA normal 区偏移（任务3）
 * @param indexOffset  IBO 区偏移（u32）
 */
public final class HeapSubregion {

    private final long baseOffset;
    private final int vertexCapacity;
    private final int posOffset;
    private final int colorOffset;
    private final int uvAbsOffset;
    private final int spriteOffset;
    private final int aoOffset;
    private final int tintOffset;
    private final int normalOffset;
    private final int indexOffset;

    public HeapSubregion(long baseOffset, int vertexCapacity, int posOffset, int colorOffset,
            int uvAbsOffset, int spriteOffset, int aoOffset, int tintOffset,
            int normalOffset, int indexOffset) {
        this.baseOffset = baseOffset;
        this.vertexCapacity = vertexCapacity;
        this.posOffset = posOffset;
        this.colorOffset = colorOffset;
        this.uvAbsOffset = uvAbsOffset;
        this.spriteOffset = spriteOffset;
        this.aoOffset = aoOffset;
        this.tintOffset = tintOffset;
        this.normalOffset = normalOffset;
        this.indexOffset = indexOffset;
    }

    /** resource heap 内偏移（字节）。 */
    public long baseOffset() {
        return baseOffset;
    }

    /** 顶点容量（R-c 预算）。 */
    public int vertexCapacity() {
        return vertexCapacity;
    }

    /** SoA pos 区绝对偏移。 */
    public long posOffset() {
        return baseOffset + posOffset;
    }

    /** SoA color 区绝对偏移。 */
    public long colorOffset() {
        return baseOffset + colorOffset;
    }

    /** SoA uvAbs 区绝对偏移（任务3 启用）。 */
    public long uvAbsOffset() {
        return baseOffset + uvAbsOffset;
    }

    /** SoA spriteID 区绝对偏移（任务3 启用）。 */
    public long spriteOffset() {
        return baseOffset + spriteOffset;
    }

    /** SoA ao 区绝对偏移（任务4 启用）。 */
    public long aoOffset() {
        return baseOffset + aoOffset;
    }

    /** SoA tint 区绝对偏移（任务3 启用）。 */
    public long tintOffset() {
        return baseOffset + tintOffset;
    }

    /** SoA normal 区绝对偏移（任务3 启用）。 */
    public long normalOffset() {
        return baseOffset + normalOffset;
    }

    /** IBO 区绝对偏移。 */
    public long indexOffset() {
        return baseOffset + indexOffset;
    }

    /**
     * 子区写窗口（供提取器经 host-visible 映射直写；任务1 接线时与堆映射层对接）。
     * 接口先立，实现随 Task 1 的堆子区规划落。
     */
    public ByteBuffer writeWindow() {
        throw new UnsupportedOperationException("Task 1: 随堆子区规划实现");
    }
}
