package dev.dhvk.mixin;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.dhvk.DhVkClient;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * S1 设备手术:给官方设备补 VK_EXT_descriptor_heap(扩展 + descriptorHeap feature)。
 *
 * <p>链机制实测(26.2, vulkan/init 包):{@code VulkanPNextStruct} 只是 (sType, size) 描述符,
 * {@code findOrCreateStructInPNextChain} 在链里找不到 sType 时**自动 ncalloc 新结构体并插链**,
 * {@code VulkanFeature.set(features2, true, stack)} 负责写字段 —— 所以手术只需把
 * {@code DESCRIPTOR_HEAP_FEATURE} 塞进官方 {@code enabledFeatures} 集合, 链与字段全由官方机制托管,
 * 无需静态结构体实例、无需拆链。
 *
 * <p>钩点(mixin 0.8.7 无 @Local 注解, 按 S0 规矩"要参数就用 @Redirect"):
 * 重定向外层 createDevice 对内层静态 createDevice 的那**唯一调用点**,
 * handler 直接拿到 deviceExtensions / physicalDevice / vulkanFeatures 三个实参,
 * 就地加料后调回原方法(原样返回, 不吞调用)。
 *
 * <p>条件式(官方 multi_draw 同款姿势):物理设备声明了该扩展才动刀;
 * 驱动没有 → 设备照常创建 → {@link DhVkClient#checkDeviceGate()} 硬门槛自禁用(不兜底, 游戏不崩)。
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendDeviceSurgeryMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanBackendDeviceSurgeryMixin.class);

    // 内层静态 createDevice 是 private: @Shadow 声明后 mixin 类内无定界符调用即可编译(应用后合入目标类, 调用 = 类内直调原方法)
    @Shadow
    private static VkDevice createDevice(Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> vulkanFeatures)
            throws BackendCreationException {
        throw new AssertionError("shadow");
    }

    private static final String OUTER_CREATE_DEVICE_DESC =
            "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;"
            + "Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;";
    private static final String INNER_CREATE_DEVICE_TARGET =
            "com/mojang/blaze3d/vulkan/VulkanBackend.createDevice(Ljava/util/Collection;"
            + "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;";

    // 结构体描述符全用 DhVkClient 的字面量常量(1.4.357 头文件实锤):
    // ⚠️ run6 实证 LWJGL 3.4.1 的 STYPE 静态字段在类初始化时刻还是 0(运行时才填充),
    // 读它会把 sType=0 焊死进链节点 → VVL 报 "unexpected APPLICATION_INFO"
    private static final VulkanPNextStruct DESCRIPTOR_HEAP_FEATURES_STRUCT = new VulkanPNextStruct(
            DhVkClient.DESCRIPTOR_HEAP_FEATURES_STYPE, DhVkClient.DESCRIPTOR_HEAP_STRUCT_SIZE);
    private static final VulkanFeature DESCRIPTOR_HEAP_FEATURE = new VulkanFeature(
            DESCRIPTOR_HEAP_FEATURES_STRUCT, "descriptorHeap", DhVkClient.DESCRIPTOR_HEAP_OFFSET);

    @Redirect(
        method = OUTER_CREATE_DEVICE_DESC,
        at = @At(value = "INVOKE", target = INNER_CREATE_DEVICE_TARGET))
    // 0.8.7 规矩(两轮实测): @Redirect 目标为**静态**方法时, handler = 目标实参 + **调用方**方法实参,
    // **不带 CallbackInfoReturnable** —— handler 的返回值直接替代原调用返回值
    private VkDevice dhvkDeviceSurgery(Collection<String> deviceExtensions, VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> vulkanFeatures, long window, ShaderSource defaultShaderSource,
            GpuDebugOptions debugOptions, Runnable criticalShaderLoader) throws BackendCreationException {
        if (DhVkClient.surgeryEnabled() && physicalDevice != null
                && physicalDevice.hasDeviceExtension("VK_EXT_descriptor_heap")) {
            deviceExtensions.add("VK_EXT_descriptor_heap");
            vulkanFeatures.add(DESCRIPTOR_HEAP_FEATURE);
            // 依赖闭包(vk.xml L23693: ((extended_flags,maintenance5)|(buffer_device_address,1.2)),1.4):
            // run7 实证 VVL 点名 maintenance5; 把驱动暴露的闭包成员全部启用, 覆盖两条"或"路径
            if (physicalDevice.hasDeviceExtension("VK_KHR_maintenance5")) {
                deviceExtensions.add("VK_KHR_maintenance5");
            }
            if (physicalDevice.hasDeviceExtension("VK_KHR_extended_flags")) {
                deviceExtensions.add("VK_KHR_extended_flags");
            }
            if (physicalDevice.hasDeviceExtension("VK_KHR_buffer_device_address")) {
                deviceExtensions.add("VK_KHR_buffer_device_address");
                // run13 根因 ③: DEVICE_ADDRESS 内存分配位 / vkGetBufferDeviceAddress 都要求设备
                // 启用 bufferDeviceAddress feature. 复用官方 VK12_FEATURES_STRUCT 链节点
                // (timelineSemaphore/hostQueryReset 同款), 支持位查询与字段写入全由官方机制托管
                vulkanFeatures.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                        VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
            }
            DhVkClient.surgeryApplied = true;
            LOGGER.info(
                    "[dhvk] device surgery applied: VK_EXT_descriptor_heap + dep closure + descriptorHeap/"
                            + "bufferDeviceAddress features");
        }
        // 调回原方法(实参已被就地加料; 负门槛 build 或驱动无扩展时原样透传)
        return createDevice(deviceExtensions, physicalDevice, vulkanFeatures);
    }

    private static final String INNER_CREATE_DEVICE_DESC =
            "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;"
            + "Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;";
    private static final String VK_CREATE_DEVICE_TARGET =
            "org/lwjgl/vulkan/VK12.vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;"
            + "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
            + "Lorg/lwjgl/PointerBuffer;)I";

    @Redirect(method = INNER_CREATE_DEVICE_DESC, at = @At(value = "INVOKE", target = VK_CREATE_DEVICE_TARGET))
    // 0.8.7 第三轮实测: **异类**静态靶子(VK12.vkCreateDevice)的 @Redirect handler 必须是 static
    // (同类静态靶子 + 非静态 handler 已被 run3 实证可用, 外层手术不动)
    //
    // 链净化器: run6 实证 sType=0 节点的真凶 = LWJGL 3.4.1 半初始化的 STYPE 静态字段
    // (结构体描述符在建链时刻读到 0 并写进节点 → VVL 报 "unexpected APPLICATION_INFO",
    // 驱动收不到堆结构体); 节点内存本身完好(24 字节保留区, 无越界)。
    // 描述符已全切字面量 1000135009; 净化器再加双保险: unlink 全部 sType=0 / 已有堆节点,
    // 在**独立栈**(32 字节对齐保留)重建我们自己的堆节点并头插, vkCreateDevice 时独立栈仍存活。
    private static int dhvkDeviceChainSanitizer(VkPhysicalDevice pdev, VkDeviceCreateInfo ci, VkAllocationCallbacks alc,
            PointerBuffer ptr, Collection<String> deviceExtensions, VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> vulkanFeatures) {
        if (DhVkClient.surgeryEnabled() && physicalDevice != null
                && physicalDevice.hasDeviceExtension("VK_EXT_descriptor_heap")) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                dhvkSanitizeChain(ci, stack);
                return VK12.vkCreateDevice(pdev, ci, alc, ptr);
            }
        }
        return VK12.vkCreateDevice(pdev, ci, alc, ptr);
    }

    private static final String CREATE_VMA_DESC = "createVma(Lorg/lwjgl/vulkan/VkDevice;)J";
    // run15 教训: 靶子描述符的返回类型必须与真方法一致 —— vmaCreateAllocator 返回 **int**
    // (栞写成 )J → 0 target scanned → mixin apply 炸); handler 返回类型同理必须 int
    private static final String VMA_CREATE_ALLOCATOR_TARGET =
            "org/lwjgl/util/vma/Vma.vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;"
                    + "Lorg/lwjgl/PointerBuffer;)I";

    @Redirect(method = CREATE_VMA_DESC, at = @At(value = "INVOKE", target = VMA_CREATE_ALLOCATOR_TARGET))
    // VMA 内存池要认识 DEVICE_ADDRESS 内存类型, 才能给 BDA 几何体(vbo/ibo/arena)分配设备可寻址内存:
    // 补 VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT. 异类静态靶子 → handler 必须 static(第三轮实测规矩)
    private static int dhvkVmaAllocatorSurgery(VmaAllocatorCreateInfo createInfo, PointerBuffer pointer,
            VkDevice vkDevice) {
        if (DhVkClient.surgeryApplied) {
            createInfo.flags(createInfo.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        }
        return Vma.vmaCreateAllocator(createInfo, pointer);
    }

    // 链净化器: 官方栈上 sType=0(STYPE 半初始化写歪)/ 已存在的堆节点全部摘除,
    // 独立栈上重建堆节点头插。官方 6 节点保持原相对顺序重链, 对驱动语义无副作用。
    private static void dhvkSanitizeChain(VkDeviceCreateInfo ci, MemoryStack stack) {
        long f2 = ci.pEnabledFeatures().address() - VkPhysicalDeviceFeatures2.FEATURES;
        long[] keep = new long[16];
        int k = 0;
        long p = VkPhysicalDeviceProperties2.npNext(f2);
        while (p != 0L && k < 16) {
            int st = VkPhysicalDeviceProperties2.nsType(p);
            if (st != 0 && st != DhVkClient.DESCRIPTOR_HEAP_FEATURES_STYPE) {
                keep[k++] = p;
            }
            p = VkPhysicalDeviceProperties2.npNext(p);
        }
        for (int i = 0; i < k; i++) {
            VkPhysicalDeviceProperties2.npNext(keep[i], i + 1 < k ? keep[i + 1] : 0L);
        }
        long node = stack.ncalloc(32, 1, 32);
        VkPhysicalDeviceProperties2.nsType(node, DhVkClient.DESCRIPTOR_HEAP_FEATURES_STYPE);
        VkPhysicalDeviceProperties2.npNext(node, k > 0 ? keep[0] : 0L);
        MemoryUtil.memPutInt(node + DhVkClient.DESCRIPTOR_HEAP_OFFSET, 1);
        VkPhysicalDeviceProperties2.npNext(f2, node);
        ci.pNext(node);
        LOGGER.info("[dhvk] chain sanitized: {} official nodes + dhvk descriptor-heap node at head", k);
    }

}
