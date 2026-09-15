package dev.dhvk;

import net.fabricmc.api.ClientModInitializer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorHeapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** dhvk 客户端入口点。 */
public final class DhVkClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DhVkClient.class);

    /**
     * descriptor-heap 物理设备特征结构体 sType = 1.4.357 头文件 674 行
     * VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT 的实锤值。
     * <p>⚠️ 勿读 LWJGL 的 {@code VkPhysicalDeviceDescriptorHeapFeaturesEXT.STYPE} 静态字段:
     * 3.4.1 代里它是运行时才填充的半初始化字段, 类初始化时刻(mixin apply)读还是 0
     * (run6 实证: 链节点 sType=0 → VVL 报 "unexpected APPLICATION_INFO")。
     */
    public static final int DESCRIPTOR_HEAP_FEATURES_STYPE = 1000135009;
    /** descriptorHeap bool 字段结构体内偏移 = 16 (sType 8 + pNext 8)。 */
    public static final int DESCRIPTOR_HEAP_OFFSET = 16;
    /** 结构体总大小 = 24 (sType 8 + pNext 8 + descriptorHeap 4 + descriptorHeapCaptureReplay 4)。 */
    public static final int DESCRIPTOR_HEAP_STRUCT_SIZE = 24;

    /** 设备硬门槛未通过 → mod 自禁用(明确日志, 游戏照常跑 vanilla 渲染器)。任务 2~5 的初始化都先查它。 */
    public static volatile boolean disabled = false;

    /** 设备手术是否对**本设备**真正生效(唯一设备级真相): vkGetPhysicalDeviceFeatures2 只查
     *  物理设备能力, 不查设备启用状态(run10 实证: 无手术时物理设备仍报 descriptorHeap=1,
     *  旧门槛逻辑误判 PASSED)。由手术 handler 在扩展+feature 真正入链时置位。 */
    public static volatile boolean surgeryApplied = false;

    private static volatile boolean gateRun = false;

    /** 供 mixin 使用:ClientPackSource 构造器早于入口点执行,共享本类日志器。 */
    public static void logDevHookInstalled() {
        LOGGER.info("[dhvk] developmentConfig hook installed: classpath assets/ dirs exposed to vanilla pack");
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[dhvk] client entrypoint initialized (mc 26.2, fabric)");
        LOGGER.info("[dhvk] vk handles: {} (capture 在设备创建时刻, 首帧前必就绪)", VkHandles.format());
    }

    /** 负门槛测试开关: runClient 前设 DHVK_NOSURGERY=1 即跳过设备手术(验证"不兜底"路径)。 */
    public static boolean surgeryEnabled() {
        return !"1".equals(System.getenv("DHVK_NOSURGERY"));
    }

    /** 门槛是否已通过(未跑过 = 未通过)。 */
    public static boolean gatePassed() {
        return !disabled;
    }

    /**
     * 硬门槛:descriptorHeap feature 未置位 → mod 自禁用。
     * 挂载点 = 首帧 FarTerrainRenderer.ensureBuffers(设备与 VkHandles 必已就绪);幂等。
     */
    public static void checkDeviceGate() {
        if (gateRun) {
            return;
        }
        gateRun = true;
        if (!VkHandles.captured || VkHandles.physicalDevice == 0L || VkHandles.pdevWrapper == null) {
            LOGGER.error("[dhvk] device gate FAILED: vk handles not captured -> mod disabled");
            disabled = true;
            return;
        }
        if (!surgeryApplied) {
            LOGGER.error(
                    "[dhvk] device gate FAILED: device surgery not applied (device has no descriptor heap) -> "
                    + "mod disabled, vanilla renderer running");
            disabled = true;
            return;
        }
        // 用捕获的 physicalDevice 自查 descriptorHeap feature(pNext 挂 features-ext 结构体)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // sType 用字面量(见类常量注释): LWJGL 的 STYPE 静态字段在 3.4.1 是运行时半初始化字段
            VkPhysicalDeviceDescriptorHeapFeaturesEXT feat = VkPhysicalDeviceDescriptorHeapFeaturesEXT.calloc(stack)
                    .sType(DESCRIPTOR_HEAP_FEATURES_STYPE);
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            f2.pNext(feat);
            // LWJGL 3.4.1: 函数在 VK11; n 变体第一参只收 VkPhysicalDevice 对象(裸 long 不匹配,
            // 传 null 实例构造 wrapper 必 NPE, run8 实证) → 用随句柄捕获的 wrapper
            VK11.nvkGetPhysicalDeviceFeatures2(VkHandles.pdevWrapper, f2.address());
            if (feat.descriptorHeap()) {
                LOGGER.info("[dhvk] device gate PASSED: descriptorHeap feature enabled");
            } else {
                LOGGER.error(
                        "[dhvk] device gate FAILED: descriptorHeap feature not enabled -> mod disabled, "
                        + "vanilla renderer running");
                disabled = true;
            }
        }
    }
}
