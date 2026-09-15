package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanTransientMemory;
import dev.dhvk.DhVkClient;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * S1 任务 4: 官方瞬态 ring 的 VBO/IBO 块要进堆描述符就必须有设备地址 ——
 * 但官方 {@code VulkanTransientMemory} 的块缓冲 Vk usage 恒定 471
 * (=TRANSFER_SRC|TRANSFER_DST|UNIFORM_TEXEL|UNIFORM_BUFFER|INDEX_BUFFER|
 * VERTEX_BUFFER|INDIRECT_BUFFER, 2026 值空间实锤), **不含
 * SHADER_DEVICE_ADDRESS(0x00020000)** → vkGetBufferDeviceAddress2 必被 VVL 点名。
 *
 * <p>手术: {@code allocateVulkanBlock} 内对 {@code Vma.vmaCreateBuffer} 的
 * 唯一调用点 @ModifyArg(argsOnly 形态; LWJGL 3.4.1 签名实锤:
 * vmaCreateBuffer(long allocator, VkBufferCreateInfo, VmaAllocationCreateInfo,
 * LongBuffer pBuffer, PointerBuffer pAllocation, VmaAllocationInfo), 调用点末位实参
 * null)。surgeryApplied 时给 usage 补 SHADER_DEVICE_ADDRESS 位(超集位, 官方自身
 * 瞬态用途无副作用; 内存型不变: 5090 ReBAR DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT,
 * mapped 直写 + BDA 双全)。
 */
@Mixin(VulkanTransientMemory.class)
public abstract class VulkanTransientMemorySurgeryMixin {

    private static final String ALLOCATE_VULKAN_BLOCK_DESC =
            "allocateVulkanBlock(JZ)Lcom/mojang/blaze3d/vulkan/VulkanTransientMemory$VulkanAllocation;";
    private static final String VMA_CREATE_BUFFER_TARGET =
            "org/lwjgl/util/vma/Vma.vmaCreateBuffer(JLorg/lwjgl/vulkan/VkBufferCreateInfo;"
                    + "Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;"
                    + "Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I";

    /** VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT (2026 头文件实锤 0x00020000; int 空间)。 */
    private static final int USAGE_SHADER_DEVICE_ADDRESS = 0x00020000;

    // mixin 0.8.7 的 @ModifyArg 无 args/argsOnly 选择器 → handler 收全部目标实参,
    // 返回类型即被替换的那一个实参(VkBufferCreateInfo)
    @ModifyArg(
        method = ALLOCATE_VULKAN_BLOCK_DESC,
        at = @At(value = "INVOKE", target = VMA_CREATE_BUFFER_TARGET))
    private static VkBufferCreateInfo dhvkTransientBdaUsage(
            long allocator, VkBufferCreateInfo createInfo, VmaAllocationCreateInfo allocInfo,
            LongBuffer buffer, PointerBuffer allocation, VmaAllocationInfo allocationInfo) {
        if (DhVkClient.surgeryApplied) {
            createInfo.usage(createInfo.usage() | USAGE_SHADER_DEVICE_ADDRESS);
        }
        return createInfo;
    }
}
