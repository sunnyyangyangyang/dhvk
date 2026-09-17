package dev.dhvk.mixin;

import com.mojang.blaze3d.systems.GpuQueryPool;
import dev.dhvk.DhvkCommandEncoder;
import dev.dhvk.DhvkQueryPool;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
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
        // run64: 纯写入。重置移入 dhvkResetQueries —— vkCmdResetQueryPool 禁于 render pass 实例内
        // (run63 VVL 实锤), 而官方 CPU 侧 vkResetQueryPool 要求队列空闲(run62 崩溃根因)。
        long q = ((DhvkQueryPool) (Object) pool).dhvkHandle();
        if (this.currentCommandBuffer != null) {
            KHRSynchronization2.vkCmdWriteTimestamp2KHR(this.currentCommandBuffer, 65536L, q, slot);
        }
    }

    @Override
    public void dhvkResetQueries(long pool, int first, int count) {
        // CBU 侧重置(render pass 实例外, 帧图 createRenderPass 之前): 同队列按提交序,
        // 对同查询对的上一写入(两帧前的 CBU)在 GPU 上先于本重置执行 → 串行无竞态。
        if (this.currentCommandBuffer != null) {
            VK12.vkCmdResetQueryPool(this.currentCommandBuffer, pool, first, count);
        }
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
