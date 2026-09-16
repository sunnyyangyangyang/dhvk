package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanQueryPool;
import dev.dhvk.DhvkQueryPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * S2 步骤 1 探针:官方 {@code VulkanQueryPool} 的裸句柄读取面。
 *
 * <p>秒表读回({@code vkGetQueryPoolResults})需要裸 {@code VkQueryPool} 句柄,
 * 官方访问器 {@code vkQueryPool()} 为 protected → @Shadow 字段桥接
 * (与 {@link VulkanCommandEncoderProbeMixin} 同姿势)。
 */
@Mixin(VulkanQueryPool.class)
public abstract class VulkanQueryPoolProbeMixin implements DhvkQueryPool {

    @Shadow
    private long vkQueryPool;

    @Override
    public long dhvkHandle() {
        return this.vkQueryPool;
    }
}
