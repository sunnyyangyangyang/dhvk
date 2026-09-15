package dev.dhvk;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 26.2 官方渲染器实际在用的 Vulkan 原生句柄持有者。
 *
 * <p>架构原则(spec §7-3):mod 侧只依赖 native 句柄(LWJGL 地址),
 * 不引用 Mojang 内部包装类,防小版本更新重构包装类导致 mod 全线报红。
 *
 * <p>{@code physicalDevice} 自 S1 任务 0 起捕获:设备手术门槛检查
 * (vkGetPhysicalDeviceFeatures2 查 descriptorHeap)与任务 2+ 的
 * descriptor-heap 原生调用都需要 pdev 句柄。
 */
public final class VkHandles {

    private static final Logger LOGGER = LoggerFactory.getLogger(VkHandles.class);

    public static long device = 0L;
    public static long queue = 0L;
    public static long commandPool = 0L;
    public static long physicalDevice = 0L;
    public static boolean captured = false;

    /** LWJGL pdev wrapper(持活实例, 构造器 getCapabilities 需要); 随 native 句柄一并捕获。 */
    public static volatile VkPhysicalDevice pdevWrapper = null;

    /**
     * LWJGL device wrapper(S1 任务 2): n 变体堆 API(nvkCmdBindResourceHeapEXT /
     * nvkWriteResourceDescriptorsEXT / nvkGetBufferDeviceAddress)只收 handle 对象,
     * 构造器调 getCapabilities() → 必须捕获官方真 wrapper 而非自造。
     */
    public static volatile VkDevice deviceWrapper = null;

    private VkHandles() {
    }

    public static void capture(long deviceHandle, long queueHandle, long commandPoolHandle, long physicalDeviceHandle) {
        device = deviceHandle;
        queue = queueHandle;
        commandPool = commandPoolHandle;
        physicalDevice = physicalDeviceHandle;
        captured = true;
        // S0 悬案收尾: 捕获成功必须留下日志(此前"not captured"只出现在设备创建之前的启动时刻)
        LOGGER.info("[dhvk] vk handles captured: {}", format());
    }

    public static String format() {
        if (!captured) {
            return "not captured yet (probe mixin not fired)";
        }
        return String.format("device=0x%x queue=0x%x pool=0x%x pdev=0x%x",
                device, queue, commandPool, physicalDevice);
    }
}
