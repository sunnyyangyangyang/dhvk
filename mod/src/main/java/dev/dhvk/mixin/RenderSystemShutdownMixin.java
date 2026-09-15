package dev.dhvk.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dhvk.FarTerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * S0 关闭路径:官方退出序列为 Minecraft.close → RenderSystem.shutdownRenderer
 * (先关共享 buffer/UBO,最后 DEVICE.close → vkDestroyDevice)。
 * 于其 HEAD(设备尚存活)释放 dhvk 自有的 VBO/IBO,满足 VVL 对象追踪
 * "设备销毁前释放全部子对象" 的约束;覆盖所有走 shutdownRenderer 的退出路径。
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemShutdownMixin {

    @Inject(method = "shutdownRenderer", at = @At("HEAD"))
    private static void dhvkDisposeFarTerrainBuffers() {
        FarTerrainRenderer.dispose();
    }
}
