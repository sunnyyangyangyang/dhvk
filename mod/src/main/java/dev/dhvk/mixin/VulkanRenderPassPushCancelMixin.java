package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.dhvk.DhVkClient;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * run32b: 官方 per-draw 经典 push 取消器(run27-31 全黑头号嫌疑的裁决实验).
 *
 * <p>证据链(笔记 run32 设计修订, run26 CBU 审计): 官方后端在每次 draw 前、我们的
 * {@code vkCmdBindResourceHeapEXT} 之后发射
 * {@code KHRPushDescriptor.vkCmdPushDescriptorSetKHR}(VulkanRenderPass.pushDescriptors
 * 390 行, 全后端唯一的经典描述符命令); 按 descriptor-heap 规格
 * 的相互失效规则, 经典 push 立即使刚绑定的堆状态作废 → 绘制时刻静态映射 UBO 读
 * 全部落在零描述符上 → 六槽全黑(唯一非黑 = run26 纯顶点色, UBO 从未通电).
 *
 * <p>刀口 = pushDescriptors 体内 390 行那条 KHR push 调用本身(@Redirect, 六条 draw 路
 * 全部汇于 pushDescriptors): 当前管线 ∈ DHVK 堆名册 → 吞掉该调用(返回 VK_SUCCESS),
 * shader 描述符状态由堆通道独占; 经典顶点/索引缓冲绑定不受互斥失效波及(run26 硬件裁决).
 * 官方其余 pass 的管线不在名册 → 一根汗毛不碰.
 *
 * <p>run32b 修订(2026-09-15): run32a 用 @Inject(HEAD)+CallbackInfo.cancel() 对
 * pushDescriptors 做方法级取消, 实机抛出
 * {@code CancellationException: The call pushDescriptors is not cancellable}
 * (反混淆产物的方法变换对 HEAD 取消不认账, 17:42:17 首帧 in-world 崩溃). 改为
 * Redirect 方法体内唯一的 KHR push 调用: 语义等价(经典 push 永不发出), 验证块与
 * anyDescriptorDirty 复位照常执行, 不依赖任何可取消性.
 * 签名 = LWJGL 便利重载 (VkCommandBuffer, int, long, int, VkWriteDescriptorSet.Buffer) -> int.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassPushCancelMixin {

    @Shadow
    protected VulkanRenderPipeline pipeline;

    @Redirect(
        method = "pushDescriptors()V",
        at = @At(
            value = "INVOKE",
            target = "org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR"
                + "(Lorg/lwjgl/vulkan/VkCommandBuffer;IJILorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;)V"
        )
    )
    private void dhvkMaybePush(
            VkCommandBuffer commandBuffer,
            int pipelineBindPoint,
            long layout,
            int set,
            VkWriteDescriptorSet.Buffer writes
    ) {
        if (DhVkClient.surgeryApplied && DhVkClient.isDhvkPipeline(this.pipeline)) {
            return; // 经典 push 对堆管线静默吞掉, 状态由堆通道独占
        }
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commandBuffer, pipelineBindPoint, layout, set, writes);
    }
}
