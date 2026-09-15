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
 * <p>run22 起, 手术设备(surgeryApplied)上**全部**缓冲叠设备地址 usage 位 —— 唯一通道是
 * {@code VulkanGpuBuffer.Direct} ctor 里的本方法(侦察实锤, mc-src L47), 一个杠杆全量生效;
 * VMA 3.x 见到该 usage 位自动以 VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT 分配内存(run13 验证,
 * VMA allocator BDA flag 已由设备手术就位), 使 vkGetBufferDeviceAddress 合法。
 * 动机 = run22 全量静态映射: 官方 uniform ring(Globals/DynamicTransforms/Projection/Fog)
 * 的设备地址被堆描述符引用, 缺 SDA 位的缓冲在 NVIDIA 驱动上 BDA 查询恒返回 0(run19b 实证)。
 * 非手术设备(负门槛 build)行为不变。
 *
 * <p>{@link DeviceAddressUsage#DEVICE_ADDRESS} 标记位(1024)保留: 自有缓冲显式声明,
 * 语义文档化(手术设备下两者同效)。
 */
@Mixin(VulkanConst.class)
public abstract class VulkanConstBdaUsageMixin {

    @Inject(method = "bufferUsageToVk(I)I", at = @At("RETURN"), cancellable = true)
    private static void dhvkAddDeviceAddressUsage(int usage, CallbackInfoReturnable<Integer> ci) {
        if (DhVkClient.surgeryApplied) {
            ci.setReturnValue(ci.getReturnValue() | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        }
    }
}
