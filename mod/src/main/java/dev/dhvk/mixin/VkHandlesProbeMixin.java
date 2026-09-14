package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.dhvk.VkHandles;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 探针:在 26.2 官方 Vulkan 后端构造完成时抓取裸原生句柄。
 *
 * <p>目标 = {@link VulkanDevice} 构造器 RETURN 时刻(实测 26.2 反混淆树:
 * 该构造器内 graphicsQueue 已由 VulkanQueue 记录构造器完成 vkGetDeviceQueue 填充,
 * 句柄即 LWJGL 地址;commandPool 在设备构造时尚未创建,按 0L 占位,
 * 阶段二可在 VulkanCommandPool 构造器补抓)。
 */
@Mixin(VulkanDevice.class)
public abstract class VkHandlesProbeMixin {

    @Shadow
    private VkDevice vkDevice;

    @Shadow
    private VulkanQueue graphicsQueue;

    // ErrorProne 不识别 @Inject 的字节码调用点: 方法"未被源码调用"、CallbackInfo 参数"未读取"均为 mixin 惯用法假报。
    @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
    @Inject(method = "<init>", at = @At("RETURN"))
    private void dhvkCaptureVkHandles(CallbackInfo ci) {
        VkHandles.capture(this.vkDevice.address(), this.graphicsQueue.vkQueue().address(), 0L);
    }
}
