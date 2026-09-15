package dev.dhvk.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.dhvk.FarTerrainRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * S0 注入点:在官方帧图装配方法 {@code LevelRenderer.render} 中,
 * 于云 pass 之后、天气 pass 之前挂入 {@code far_terrain} pass。
 *
 * <p>锚点 = 对私有方法 {@code addWeatherPass(FrameGraphBuilder, GpuBufferSlice)} 的
 * INVOKE(实测 26.2 反混淆树 LevelRenderer.java:229,紧随 addCloudsPass 之后)。
 * 用 {@code @Redirect} 接管该调用:先挂 far_terrain pass,再原样放行 addWeatherPass。
 * (mixin 0.8.7 的 @Inject 已无 args=@Capture 成员 —— 0.8.7 注解表实测;
 *  {@code @Redirect} 拿得到 INVOKE 实参,不需要猜局部变量槽位。)
 * {@code targets} 经 @Shadow 取宿主私有字段,保持与官方各 pass 完全一致的
 * targets.main 重写语义。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFarPassMixin {

    @Shadow
    private net.minecraft.client.renderer.LevelTargetBundle targets;

    @Shadow
    private void addWeatherPass(final FrameGraphBuilder frame, final GpuBufferSlice fog) {
    }

    private static final String ADD_WEATHER_PASS =
            "Lnet/minecraft/client/renderer/LevelRenderer;addWeatherPass("
                    + "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";

    // ErrorProne 不识别 @Redirect 的字节码替换点:"未被源码调用"为 mixin 惯用法假报。
    @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
    @Redirect(method = "render", at = @At(value = "INVOKE", target = ADD_WEATHER_PASS))
    private void dhvkRedirectWeatherPass(final LevelRenderer self, final FrameGraphBuilder frame,
            final GpuBufferSlice fog) {
        FarTerrainRenderer.attach(frame, this.targets);
        this.addWeatherPass(frame, fog);
    }
}
