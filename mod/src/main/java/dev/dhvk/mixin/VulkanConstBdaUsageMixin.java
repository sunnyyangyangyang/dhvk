package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanConst;
import dev.dhvk.DeviceAddressUsage;
import dev.dhvk.DhVkClient;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S1 设备手术(usage 面): 2026 值空间没有独立 BDA usage 位 —— 缓冲设备地址统一收编到
 * {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}(131072, LWJGL 3.4.1-snapshot javap 实锤).
 *
 * <p>GpuBuffer.Usage 带 {@link DeviceAddressUsage#DEVICE_ADDRESS} 标记位(1024, 高于全部官方
 * usage 位)的缓冲, 在官方 {@code bufferUsageToVk} 返回后叠设备地址 usage 位; VMA 3.x 见到该
 * usage 位自动以 VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT 分配内存(VVL 复核),
 * 使 vkGetBufferDeviceAddress 合法. 官方缓冲不带标记位, 返回原值, 逐位不变.
 */
@Mixin(VulkanConst.class)
public abstract class VulkanConstBdaUsageMixin {

    @Inject(method = "bufferUsageToVk(I)I", at = @At("RETURN"), cancellable = true)
    private static void dhvkAddDeviceAddressUsage(int usage, CallbackInfoReturnable<Integer> ci) {
        if (DhVkClient.surgeryApplied && (usage & DeviceAddressUsage.DEVICE_ADDRESS) != 0) {
            ci.setReturnValue(ci.getReturnValue() | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        }
    }
}
