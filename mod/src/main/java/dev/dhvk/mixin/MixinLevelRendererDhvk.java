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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("dhvk.fulllog");
    private static int dhvkHeadFrames;

    private static Object dhvkField(final Class<?> cfg, final String name, final Object inst)
            throws ReflectiveOperationException {
        final java.lang.reflect.Field f = cfg.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(inst);
    }

    @Inject(at = @At("HEAD"), method = "render")
    private void dhvkHead(final CallbackInfo ci) {
        if (DhVkClient.disabled || !DhVkClient.wallEnvOn()) {
            return;
        }
        DhVkClient.uniformCaptureArmed = true;
        DhVkClient.UNIFORM_SLICES.clear();
        // run122 全量日志探针: 确认 vmArgs 的 slf4j 属性真的进了客户端 JVM
        if (dhvkHeadFrames < 2) {
            LOGGER.info("[dhvk] fulllog probe: defaultLogLevel={} (debug 行见下条, 出现即全量生效)",
                    System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
            LOGGER.debug("[dhvk] fulllog DEBUG-level probe frame={}", dhvkHeadFrames);
            // run131: 游戏内 mod classloader 看不到 slf4j-simple 实现类(CNFE 实锤) →
            // 一律走 platform CL 取根加载器的 provider 内部配置
            try {
                final ClassLoader root = ClassLoader.getPlatformClassLoader();
                final Class<?> cfg = Class.forName("org.slf4j.simple.SimpleLoggerConfiguration", true, root);
                final Object inst = cfg.getField("CONFIG_PARAMS").get(null);
                LOGGER.info("[dhvk] fulllog cfg: defaultLogLevel={} logFile={} showDateTime={} showThread={}",
                        dhvkField(cfg, "defaultLogLevel", inst), dhvkField(cfg, "logFile", inst),
                        dhvkField(cfg, "showDateTime", inst), dhvkField(cfg, "showThreadName", inst));
            } catch (ReflectiveOperationException e) {
                LOGGER.info("[dhvk] fulllog cfg probe failed: {}", e.toString());
            }
            dhvkHeadFrames++;
        }
        // 一切 CPU→GPU 上传集中在帧首 (ring 当帧第一用户窗口, 与官方场景 pass 同窗)
        FarTerrainRenderer.prepareFrameHead(this.gameRenderer.mainRenderTarget());
    }

    @Inject(at = @At("TAIL"), method = "render")
    private void dhvkTail(final CallbackInfo ci) {
        if (DhVkClient.disabled || !DhVkClient.wallEnvOn()) {
            return;
        }
        FarTerrainRenderer.dhStyleFrame(this.gameRenderer.mainRenderTarget());
    }
}
