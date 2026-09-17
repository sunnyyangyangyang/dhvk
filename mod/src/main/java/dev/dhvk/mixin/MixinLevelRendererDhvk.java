package dev.dhvk.mixin;

import dev.dhvk.DhVkClient;
import dev.dhvk.FarTerrainRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移植态帧钩子 (run89+): LevelRenderer.render 的 TAIL —— 官方场景帧图执行完之后,
 * 在设备共享 CBU 上追加我们的离屏渲染实例 (体素带 → 自建颜色/深度对),
 * 帧末由设备统一提交。与 DH 本体同节奏: 同一条命令流中途追加, 无第二 CBU、
 * 无显式 semaphore/fence (26.2 设备 CBU 单例已实证)。
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererDhvk {

    @Shadow
    private GameRenderer gameRenderer;

    /** 帧头: 张开全帧捕获窗并清表 —— 官方场景 pass 的 setUniform(尤其 DynamicTransforms)
     *  在场景执行期间被探针收进 UNIFORM_SLICES, TAIL 时直接借其 ring slice (零拷贝, run102)。 */
    @Inject(at = @At("HEAD"), method = "render")
    private void dhvkHead(final CallbackInfo ci) {
        if (DhVkClient.disabled || !DhVkClient.wallEnvOn()) {
            return;
        }
        DhVkClient.uniformCaptureArmed = true;
        DhVkClient.UNIFORM_SLICES.clear();
    }

    @Inject(at = @At("TAIL"), method = "render")
    private void dhvkTail(final CallbackInfo ci) {
        if (DhVkClient.disabled || !DhVkClient.wallEnvOn()) {
            return;
        }
        FarTerrainRenderer.dhStyleFrame(this.gameRenderer.mainRenderTarget());
    }
}
