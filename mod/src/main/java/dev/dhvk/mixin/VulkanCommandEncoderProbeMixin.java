package dev.dhvk.mixin;

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
 * <p>实现 {@link DhvkCommandEncoder} 接口 → mixin 框架把接口注入目标类,
 * 渲染器经接口类型调用, 不直接依赖官方 wrapper 类。
 */
@Mixin(com.mojang.blaze3d.vulkan.VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderProbeMixin implements DhvkCommandEncoder {

    @Shadow
    private @Nullable VkCommandBuffer currentCommandBuffer;

    @Override
    public long dhvkCurrentCbu() {
        return this.currentCommandBuffer != null ? this.currentCommandBuffer.address() : 0L;
    }
}
