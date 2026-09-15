package dev.dhvk.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.dhvk.DhVkClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S1 任务 2(run22)uniform 捕获探针:全量静态映射下, 官方 uniform 绑定(Globals/
 * DynamicTransforms/Projection/Fog)的堆描述符每帧要指向"本帧"官方 ring 缓冲的 slice
 * (ring offset 每帧滚动, 设备地址恒定)。
 *
 * <p>缝 = {@code RenderPass.setUniform} 两个公开重载的 TAIL(0.8.7 无 @Local, 形参直取;
 * 实例 @Inject 的 {@code this} 即 RenderPass, 但判定不依赖它 —— 用
 * {@link DhVkClient#uniformCaptureArmed} 捕获窗过滤: 窗口只在自有远地形 pass 的
 * setUniform 段落期间张开(render 线程单线程, 无竞态, 与 surgeryApplied 同模式),
 * 官方其余 pass 的 setUniform 调用不进表。
 */
@Mixin(RenderPass.class)
public abstract class RenderPassUniformProbeMixin {

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("TAIL"))
    private void dhvkCaptureSliceUniform(String name, GpuBufferSlice slice, CallbackInfo ci) {
        if (DhVkClient.uniformCaptureArmed) {
            DhVkClient.UNIFORM_SLICES.put(name, slice);
        }
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
            at = @At("TAIL"))
    private void dhvkCaptureWholeBufferUniform(String name, GpuBuffer buffer, CallbackInfo ci) {
        if (DhVkClient.uniformCaptureArmed) {
            DhVkClient.UNIFORM_SLICES.put(name, new GpuBufferSlice(buffer, 0L, buffer.size()));
        }
    }
}
