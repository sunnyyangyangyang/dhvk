package dev.dhvk;

import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
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

    // 任务 3(VRS 门槛): 2026 的 VRS = VK_KHR_fragment_shading_rate
    // (registry 已移除 VK_EXT_variable_rate_image; 5090 驱动暴露 FSR rev2,
    // pipeline/primitive/attachment 三 feature 全 true —— vulkaninfo 实测)。
    /** sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_FEATURES_KHR (2026 头文件实锤)。 */
    public static final int FSR_FEATURES_STYPE = 1000226003;
    /** pipelineFragmentShadingRate bool 字段结构体内偏移 = 16 (sType 8 + pNext 8)。 */
    public static final int FSR_PIPELINE_FRAGMENT_SHADING_RATE_OFFSET = 16;
    /** 结构体总大小 = 32 (sType 8 + pNext 8 + 三个 VkBool32 + 填充, 头文件实测)。 */
    public static final int FSR_FEATURES_STRUCT_SIZE = 32;

    /** 设备硬门槛未通过 → mod 自禁用(明确日志, 游戏照常跑 vanilla 渲染器)。任务 2~5 的初始化都先查它。 */
    public static volatile boolean disabled = false;

    /** 设备手术是否对**本设备**真正生效(唯一设备级真相): vkGetPhysicalDeviceFeatures2 只查
     *  物理设备能力, 不查设备启用状态(run10 实证: 无手术时物理设备仍报 descriptorHeap=1,
     *  旧门槛逻辑误判 PASSED)。由手术 handler 在扩展+feature 真正入链时置位。 */
    public static volatile boolean surgeryApplied = false;

    /** run20 管线手术上膛窗: 仅在 FarTerrainRenderer.ensureHeapSurgery 强制首编 PIPELINE 期间为 true
     *  (render 线程内 set/clear, 无竞态; 与 surgeryApplied 同模式)。VulkanRenderPipelineSurgeryMixin
     *  的 @Redirect handler 读它决定是否把 descriptor-heap 创建位/静态映射链注入官方 compile()。 */
    public static volatile boolean pipelineSurgeryArmed = false;

    /** 任务 3(VRS 门槛): 是否在 dhvk 管线注入 2x2 fragmentShadingRate 状态节点。
     *  由设备手术 handler 在设备创建时置位(扩展在场 + DHVK_VRS 环境开关);
     *  VulkanRenderPipelineSurgeryMixin 读它决定 pNext 链头是否加 FSR 状态节点。 */
    public static volatile boolean vrsEnabled = false;

    /** VRS 因子 A/B 开关: DHVK_VRS=0 强制关管线率态节点(设备扩展/feature 照常加,
     *  两次跑设备配置恒同, 唯一差 = 管线态节点)。默认开。 */
    public static boolean vrsEnvOn() {
        return !"0".equals(System.getenv("DHVK_VRS"));
    }

    /** S1 任务 4 目验收口 A/B 因子: DHVK_NOWALL=1 → far_terrain pass 整个不挂
     *  (墙与探针色板全消失, 画面回到官方原生观感, 用于裁定"屏上到底是谁的")。默认开。 */
    public static boolean wallEnvOn() {
        return !"1".equals(System.getenv("DHVK_NOWALL"));
    }

    /** S1 任务 4 目验收口 A/B 因子: DHVK_NOPROBE=1 → 只绘墙, 不绘 NDC 钉死的探针色板
     *  (墙与探针分离, 用于裁定"糊在镜头上的薄片"的归属)。默认开。 */
    public static boolean probeEnvOn() {
        return !"1".equals(System.getenv("DHVK_NOPROBE"));
    }

    /** S2v2 步骤 15: 合成调试环 A/B 因子（高度场退役后保留, 规格 §0: 降级为机制验收
     *  工具）: DHVK_SYNTRING=1 → 远 pass 几何换回 S1 合成环(VoxelWallSynthesizer),
     *  用于瞬态环上传/绘制/堆表机制的独立再验证。默认关 = 贪心带(生产路径)。 */
    public static boolean synthRingOn() {
        return "1".equals(System.getenv("DHVK_SYNTRING"));
    }

    /** 目验收调试因子: DHVK_MESH_LIFT=N(块) → 远带几何整体上移 N 块(生产默认 0; 只移
     *  顶点, 不动 Y 采样窗)。用途: 平坦地形上带面与近程地形共面 → 不可见(深度平局
     *  近程胜, run72 定谳); 抬升后纸片高出草海, 仰角/侧视即可目验。 */
    public static int meshLift() {
        String m = System.getenv("DHVK_MESH_LIFT");
        if (m == null || m.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(m);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** S2v2 任务 1: 贪心 tile 带原点覆写 "x z"（世界 block；缺省 = 首帧玩家位置）。
     *  用途: 出生点与目验目标 chunk 不一致时钉住 tile 带。 */
    public static String meshAt() {
        return System.getenv("DHVK_MESH_AT");
    }

    /** run20: VkShaderDescriptorSetAndBindingMappingInfoEXT 节点指针(native heap 永生, 0 = 尚未建)。
     *  由 ensureHeapSurgery 建好并置位; 管线销毁晚于我们的 dispose, 节点不回收。 */
    public static volatile long mappingInfoNode = 0L;

    /** run22 uniform 捕获窗: 仅在自有远地形 pass 的 setUniform 段落期间为 true(render 线程
     *  set/clear, 与 pipelineSurgeryArmed 同模式)。RenderPassUniformProbeMixin 的 @Inject
     *  handler 读它决定是否把 (uniform 名 → 本帧 slice) 记入 uniformSlices。 */
    public static volatile boolean uniformCaptureArmed = false;

    /** run22: 捕获窗内的 uniform 名 → 本帧 GpuBufferSlice 记录(render 线程独占, 每帧窗口清空)。
     *  全量静态映射下, 官方 uniform 绑定的堆描述符每帧从此处取 slice 重写(笔记 run22 设计定稿)。 */
    public static final Map<String, com.mojang.blaze3d.buffers.GpuBufferSlice> UNIFORM_SLICES = new HashMap<>();

    /** run32 Ace2: DHVK 堆管线名册(身份集合). 名册管线 draw 触发官方
     *  {@code VulkanRenderPass.pushDescriptors} 时, mixin 在 HEAD 整段取消经典 push ——
     *  descriptor-heap 相互失效规则: 官方经典 push 落在我们堆绑定之后会作废堆状态
     *  → 绘制时刻静态映射 UBO 读全部落在零描述符上(run27-31 全黑根因嫌疑,
     *  见笔记 run32 设计修订). render 线程注册、同线程读取, 无跨线程问题. */
    public static final Set<VulkanRenderPipeline> DHVK_PIPELINES = Collections.newSetFromMap(new IdentityHashMap<>());

    /** run32 Ace2: 把手术编译出的堆管线登记进 push 取消名册(precipile/getOrCompilePipeline
     *  缓存同源 → 编译实例唯一; 同一管线对象重复传入幂等无害). */
    public static void registerDhvkPipeline(VulkanRenderPipeline pipeline) {
        DHVK_PIPELINES.add(pipeline);
    }

    /** run32 Ace2: 给定编译管线是否为 DHVK 堆管线之一(VulkanRenderPassPushCancelMixin 判据). */
    public static boolean isDhvkPipeline(VulkanRenderPipeline pipeline) {
        return pipeline != null && DHVK_PIPELINES.contains(pipeline);
    }

    /** run32 Ace2: 名册规模(供日志). */
    public static int dhvkPipelineCount() {
        return DHVK_PIPELINES.size();
    }

    private static volatile boolean gateRun = false;

    /** 供 mixin 使用:ClientPackSource 构造器早于入口点执行,共享本类日志器。 */
    public static void logDevHookInstalled() {
        LOGGER.info("[dhvk] developmentConfig hook installed: classpath assets/ dirs exposed to vanilla pack");
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[dhvk] client entrypoint initialized (mc 26.2, fabric)");
        LOGGER.info("[dhvk] far_terrain gate: wall={}, probe={}, synth={} (DHVK_NOWALL={}, "
                        + "DHVK_NOPROBE={}, DHVK_SYNTRING={})",
                wallEnvOn(), probeEnvOn(), synthRingOn(),
                System.getenv("DHVK_NOWALL"), System.getenv("DHVK_NOPROBE"), System.getenv("DHVK_SYNTRING"));
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
