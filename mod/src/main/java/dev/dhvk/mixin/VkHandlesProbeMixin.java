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
 *
 * <p>pdev 句柄取法(S1 任务 0 实测修正,偏离 plan Step 8 的 @Shadow 设想):
 * 26.2 的 {@code VulkanDevice} 没有 physical device 字段——构造器参数
 * {@code VulkanPhysicalDevice} 是包装类且在构造末尾被 close()。改为经
 * LWJGL {@code VkDevice#getPhysicalDevice()} 取裸 pdev(官方同款用法,
 * 见官方 {@code VulkanBackend} L199/L440;LWJGL 3.4.1 该 API 已 javap 核实)。
 * pdev 属 instance 级,地址值在 device 存活期内稳定。
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
        VkHandles.capture(this.vkDevice.address(), this.graphicsQueue.vkQueue().address(),
                0L, this.vkDevice.getPhysicalDevice().address());
        // S1 任务1: 连 wrapper 一起捕获(LWJGL 3.4.1 n 变体只收 handle 对象, 且
        // VkPhysicalDevice 构造器调 instance.getCapabilities() → 传 null 实例必 NPE, run8 实证)
        VkHandles.pdevWrapper = this.vkDevice.getPhysicalDevice();
        // S1 任务2: device wrapper 供堆 API n 变体(new VkCommandBuffer(cbu, deviceWrapper) 等)
        VkHandles.deviceWrapper = this.vkDevice;
    }
}
