package dev.dhvk.mixin;

import dev.dhvk.DhVkClient;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S0 修复:26.2 的 vanilla pack(VanillaPackResourcesBuilder)只挂文件系统根
 * (client jar 经 .mcassetsroot 探测 + --assetsDir),classpath 资源要进 pack 栈
 * 必须走 developmentConfig 钩子 —— 该钩子在 26.2 原版是 no-op,且
 * fabric-loom 1.17.20 / fabric-loader 0.19.5 工具链均不设它(全 classpath 字节码普查),
 * 于是 mod 的 build/resources/main/assets/** 对 pack 系统不可见:
 * ShaderManager 在 reload 时找不到 dhvk:core/far_terrain 的着色器源码,
 * 首帧懒编译管线无效 → "Pipeline is not valid (may contain invalid shaders?)"。
 *
 * <p>于 ClientPackSource 构造器 HEAD(super() 之前,即 createVanillaPackSource
 * 读取 developmentConfig 之前)接管钩子,两件事缺一不可:
 * <ol>
 *   <li>{@code pushClasspathResources} 把 classpath 上 file: scheme 的 assets/
 *       目录(mod 资源目录)推进 vanilla pack 的文件系统根;</li>
 *   <li>{@code exposeNamespace("dhvk")} —— 26.2 的 MultiPackResourceManager 按
 *       pack.getNamespaces() 的并集预建"命名空间 → 管理器"表,而 vanilla pack 的
 *       该集合由 builder 的 exposeNamespace 定死(原版只报 minecraft/realms),
 *       不暴露 dhvk 则 listResources 根本不会枚举我们的命名空间。</li>
 * </ol>
 * release 环境 mod 资源在 jar 内(jar: scheme),pushClasspathResources 不添加
 * 任何根;exposeNamespace 只扩大命名空间声明,对无资源的命名空间是空操作。
 */
@Mixin(ClientPackSource.class)
public abstract class ClientPackSourceDevResourcesMixin {

    // 构造器 @At("HEAD") 位于 super() 之前 —— mixin 规定此类 handler 必须 static(此时 this 尚不存在)。
    @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void dhvkExposeClasspathAssetRoots(final CallbackInfo ci) {
        VanillaPackResourcesBuilder.developmentConfig = builder -> {
            builder.pushClasspathResources(PackType.CLIENT_RESOURCES, VanillaPackResourcesBuilder.class);
            builder.exposeNamespace("dhvk");
        };
        DhVkClient.logDevHookInstalled();
    }
}
