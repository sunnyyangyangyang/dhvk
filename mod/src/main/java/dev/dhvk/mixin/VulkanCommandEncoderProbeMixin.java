package dev.dhvk.mixin;

import com.mojang.blaze3d.systems.GpuQueryPool;
import dev.dhvk.DhvkCommandEncoder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * S1 任务 2 探针:读出官方 encoder 当前录制中的 CBU(裸 VkCommandBuffer 句柄),
 * 供 {@code DescriptorHeap.bind} 的每帧 vkCmdBindResourceHeapEXT 发射点(笔记 §2:
 * setPipeline 之后、drawIndexed 之前, 与官方 pass 录制同一 CBU)。
 *
 * <p>S2 步骤 1:再加秒表面 —— timeline semaphore 句柄(读回安全判据) + 本帧
 * submitIndex(在途登记) + writeTimestamp 委托(官方公开方法, s2 笔记 §1/§2)。
 *
 * <p>实现 {@link DhvkCommandEncoder} 接口 → mixin 框架把接口注入目标类,
 * 渲染器经接口类型调用, 不直接依赖官方 wrapper 类。
 */
@Mixin(com.mojang.blaze3d.vulkan.VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderProbeMixin implements DhvkCommandEncoder {

    @Shadow
    private @Nullable VkCommandBuffer currentCommandBuffer;

    @Shadow
    private long submitSemaphore;

    @Shadow
    private long currentSubmitIndex;

    @Override
    public long dhvkCurrentCbu() {
        return this.currentCommandBuffer != null ? this.currentCommandBuffer.address() : 0L;
    }

    @Override
    public void dhvkWriteTimestamp(GpuQueryPool pool, int slot) {
        // mixin 类编译期非 target 子类(无 @Shadow 方法桩), 运行时才是 → 经 Object 双投
        // 调官方公开方法(与渲染器侧 ((DhvkCommandEncoder)(Object)encoder) 同姿势)
        ((com.mojang.blaze3d.vulkan.VulkanCommandEncoder) (Object) this).writeTimestamp(pool, slot);
    }

    @Override
    public long dhvkSubmitSemaphore() {
        return this.submitSemaphore;
    }

    @Override
    public long dhvkCurrentSubmitIndex() {
        return this.currentSubmitIndex;
    }
}
