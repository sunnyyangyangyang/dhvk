package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.dhvk.DhVkClient;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineCreateFlags2CreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderDescriptorSetAndBindingMappingInfoEXT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * S1 任务 2(run20)管线手术:把官方 {@code VulkanRenderPipeline.compile} 的管线创建改造成
 * 2026 descriptor-heap 形态(规约 /tmp/dh-spec.txt):
 *
 * <ul>
 *   <li>绑定→堆源的 mapping 不再是每帧命令级链, 而是管线创建期挂在
 *       {@code VkPipelineShaderStageCreateInfo.pNext} 上的静态声明
 *       ({@code VkShaderDescriptorSetAndBindingMappingInfoEXT});</li>
 *   <li>管线必须带 {@code VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT}
 *       (2026 值 = 68719476736L, 64 位, 32 位 flags 字段装不下) → 经
 *       {@code VkPipelineCreateFlags2CreateInfo}(sType 1000470005) pNext 节点注入;</li>
 *   <li>每帧 {@code vkCmdBindResourceHeapEXT} 只带堆范围 + 预留区, pNext 必须 NULL
 *       (在 {@code DescriptorHeap.bind} 侧落地);</li>
 *   <li>任务 3(VRS 门槛): {@code vrsEnabled} 时链头再前置一个
 *       {@code VkPipelineFragmentShadingRateStateCreateInfoKHR}(2026 形态, 无 rate 图像,
 *       fragmentSize=2x2 固定率, combiner KEEP)→ dhvk 管线成为 pass 级 2x2 粗化。</li>
 * </ul>
 *
 * <p>手术缝(官方 compile() 是纯静态方法、全部结构体在栈上, 只能 @Redirect 其内部
 * lwjgl setter 调用; 靶描述符 = javap + 官方反编译源码双验):
 *
 * <ul>
 *   <li>靶1: 官方 L197 {@code createInfo.pNext(renderingInfo)} 的字节码落在
 *       {@code VkGraphicsPipelineCreateInfo$Buffer.pNext(VkPipelineRenderingCreateInfoKHR)}
 *       (createInfo 是 $Buffer 包装型)。armed 时每 compile 建一个永生 flags2 节点
 *       (native heap: VVL 1.4.341 持 pNext 节点裸指针, run15/17 教训), 链头改为
 *       flags2 节点 → pNext 指向本次 compile 的栈上 renderingInfo(compile() 返回前恒活,
 *       vkCreateGraphicsPipelines 两次调用点均在其存活期内)。</li>
 *   <li>靶2: 官方 L71/76 {@code stage.pName(nameMain)}(nameMain = stack.UTF8("main") 是
 *       **ByteBuffer**, 3.4.1 无 pName(Pointer) 重载)。armed 且
 *       {@link DhVkClient#mappingInfoNode} 已建 → VS/FS 双 stage 都挂静态映射 info 节点后
 *       透传(run22: 11312 全量规则, Fog 在片元)。</li>
 *   <li>靶3: 官方 L196 {@code createInfo.layout(pipelineLayout)}(实参是裸 long)。
 *       run20 实证 VUID-flags-11311: descriptor-heap 位管线 layout 必须 VK_NULL_HANDLE
 *       (规约: 静态映射整体取代 set/pipeline layout) → armed 时喂 0L。</li>
 * </ul>
 */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineSurgeryMixin {

    // 2026 值空间字面量(LWJGL 3.4.1 新生代结构体 STYPE 半初始化 = 0, run6 教训 → 一律字面量)
    /** VK_STRUCTURE_TYPE_PIPELINE_CREATE_FLAGS_2_CREATE_INFO (VK14)。 */
    private static final int STYPE_PIPELINE_CREATE_FLAGS_2 = 1000470005;
    /** VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT (2026 = 2^36, 64 位)。 */
    private static final long FLAGS2_DESCRIPTOR_HEAP = 68719476736L;
    /** VK_STRUCTURE_TYPE_PIPELINE_FRAGMENT_SHADING_RATE_STATE_CREATE_INFO_KHR (2026 头文件实锤)。 */
    private static final int STYPE_PIPELINE_FRAGMENT_SHADING_RATE_STATE = 1000226001;
    /** S1 VRS 门槛: dhvk 管线片元率 2x2(2026 管线态固定率, 零 per-frame 开销)。 */
    private static final int DHVK_FRAGMENT_SIZE_X = 2;
    private static final int DHVK_FRAGMENT_SIZE_Y = 2;

    // 靶描述符: 字节码 invoke 的**精确**形态(异类静态/实例 setter 目标 → handler 必须 static,
    // run15 规矩: 目标描述符与 handler 签名逐字符一致, 错一个字母 = 0 target scanned)
    private static final String PIPELINE_CREATEINFO_PNEXT_TARGET =
            "org/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer.pNext(Lorg/lwjgl/vulkan/"
                    + "VkPipelineRenderingCreateInfoKHR;)Lorg/lwjgl/vulkan/"
                    + "VkGraphicsPipelineCreateInfo$Buffer;";
    private static final String STAGE_CREATEINFO_PNAME_TARGET =
            "org/lwjgl/vulkan/VkPipelineShaderStageCreateInfo.pName(Ljava/nio/ByteBuffer;)"
                    + "Lorg/lwjgl/vulkan/VkPipelineShaderStageCreateInfo;";
    private static final String PIPELINE_CREATEINFO_LAYOUT_TARGET =
            "org/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer.layout(J)"
                    + "Lorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;";

    @Redirect(method = "compile", at = @At(value = "INVOKE", target = PIPELINE_CREATEINFO_PNEXT_TARGET))
    // 每 compile 新建永生节点(不复用: 节点 pNext 指向"本次" compile 的栈上 renderingInfo,
    // 跨 compile 复用会让第二次调用吃到悬空链)。节点本体留 native heap 不回收
    // (VVL 的 pNext walker 在调用之后任意时刻仍可能摸该裸指针)
    private static VkGraphicsPipelineCreateInfo.Buffer dhvkPipelineFlags2(
            VkGraphicsPipelineCreateInfo.Buffer createInfo, VkPipelineRenderingCreateInfoKHR renderingInfo) {
        if (DhVkClient.pipelineSurgeryArmed) {
            long node = MemoryUtil.nmemCalloc(32, 16);
            VkPipelineCreateFlags2CreateInfo.create(node)
                    .sType(STYPE_PIPELINE_CREATE_FLAGS_2)
                    .pNext(renderingInfo.address()) // 3.4.1: struct 侧 pNext 只有 long 重载, 无 typed 变体
                    .flags(FLAGS2_DESCRIPTOR_HEAP);
            if (DhVkClient.vrsEnabled) {
                // 任务 3(VRS 门槛): 2026 VK_KHR_fragment_shading_rate 管线态固定 2x2 率
                // (S1 门槛: dhvk 管线 pass 级粗化, 官方管线不动 → 因子 A/B 的唯一差异点)。
                // 头文件实测(1.4.357 与 2026 main 同形): sType@0, pNext@8,
                // fragmentSize@16/20, combinerOps@24/28, sizeof=32。
                // 全字面量写入(run6 教训: 不读 LWJGL 半初始化 STYPE 静态); combiner KEEP=0。
                // 2026 形态无 rate 图像: 管线态 fragmentSize 即生效率, 零 per-frame 开销;
                // 链头 = fsr 节点 -> flags2 节点 -> renderingInfo。
                long fsr = MemoryUtil.nmemCalloc(32, 16);
                // LWJGL 3.4.1(-unsafe) 的 MemoryUtil 只有两参 memPut*(address, value), 无 nmemPut*
                // (实锤: 真 jar javap; 净化器同款用法) → 偏移直接加进地址
                MemoryUtil.memPutInt(fsr, STYPE_PIPELINE_FRAGMENT_SHADING_RATE_STATE); // sType @0
                MemoryUtil.memPutAddress(fsr + 8, node); // pNext @8 -> flags2 节点
                MemoryUtil.memPutInt(fsr + 16, DHVK_FRAGMENT_SIZE_X); // fragmentSize.width @16
                MemoryUtil.memPutInt(fsr + 20, DHVK_FRAGMENT_SIZE_Y); // fragmentSize.height @20
                MemoryUtil.memPutInt(fsr + 24, 0); // combinerOps[0] @24 = KEEP
                MemoryUtil.memPutInt(fsr + 28, 0); // combinerOps[1] @28 = KEEP
                return createInfo.pNext(fsr);
            }
            return createInfo.pNext(node);
        }
        return createInfo.pNext(renderingInfo);
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE", target = STAGE_CREATEINFO_PNAME_TARGET))
    // run22: VS/FS **双 stage** 都挂同一 info 节点 —— 11312 是全量规则, Fog 在片元 shader
    // (run21 VVL 点名 pStages[1]); 若 VVL 后续报"映射了本 stage 接口外的 binding",
    // run23 再按 stage 拆映射子集(听 VVL 点名, 不预设)
    private static VkPipelineShaderStageCreateInfo dhvkStageMapping(VkPipelineShaderStageCreateInfo stageInfo,
            ByteBuffer name) {
        if (DhVkClient.pipelineSurgeryArmed && DhVkClient.mappingInfoNode != 0L) {
            stageInfo.pNext(VkShaderDescriptorSetAndBindingMappingInfoEXT.create(DhVkClient.mappingInfoNode));
        }
        return stageInfo.pName(name);
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE", target = PIPELINE_CREATEINFO_LAYOUT_TARGET))
    // run20 实证 VUID-VkGraphicsPipelineCreateInfo-flags-11311: 带 descriptor-heap 创建位的管线
    // layout 必须 VK_NULL_HANDLE(2026 规约: "In place of descriptor set layouts and pipeline
    // layouts" —— 静态映射整体取代 set/pipeline layout)。官方 L55 建的 vkPipelineLayout 对象
    // 仍照常创建/销毁(VVL 对象追踪闭合), 只是不再喂给 createInfo
    private static VkGraphicsPipelineCreateInfo.Buffer dhvkPipelineNullLayout(
            VkGraphicsPipelineCreateInfo.Buffer createInfo, long pipelineLayout) {
        if (DhVkClient.pipelineSurgeryArmed) {
            return createInfo.layout(0L);
        }
        return createInfo.layout(pipelineLayout);
    }
}
