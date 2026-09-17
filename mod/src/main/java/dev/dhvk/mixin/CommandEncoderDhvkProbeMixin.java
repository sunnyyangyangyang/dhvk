package dev.dhvk.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import dev.dhvk.DhvkCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * S1 任务 2 探针:官方 {@code systems.CommandEncoder} wrapper 的 CBU 读取面。
 *
 * <p>{@code GpuDevice.createCommandEncoder()} 返回的是 wrapper(私有 backend 字段,
 * protected 访问器 → mod 侧拿不到具体后端) → 本 mixin 经 @Shadow 字段桥接到
 * Vulkan 后端的 {@link DhvkCommandEncoder} 实现(wrapper 自身也注入该接口,
 * 渲染器只依赖接口)。非 Vulkan 后端(模态自禁用时) → 0。
 *
 * <p>S2 步骤 1:秒表三方法同样经 backend 委托(非 Vulkan 后端 = 无效值/空操作)。
 */
@Mixin(com.mojang.blaze3d.systems.CommandEncoder.class)
public abstract class CommandEncoderDhvkProbeMixin implements DhvkCommandEncoder {

    @Shadow
    private CommandEncoderBackend backend;

    @Override
    public long dhvkCurrentCbu() {
        return this.backend instanceof DhvkCommandEncoder probe ? probe.dhvkCurrentCbu() : 0L;
    }

    @Override
    public void dhvkWriteTimestamp(GpuQueryPool pool, int slot) {
        if (this.backend instanceof DhvkCommandEncoder probe) {
            probe.dhvkWriteTimestamp(pool, slot);
        }
    }

    @Override
    public void dhvkResetQueries(long pool, int first, int count) {
        // run64 修: 26.2 createCommandEncoder() 返回的是本 wrapper(backend 私有字段)——
        // 接口注入在 wrapper 与 backend 两层, 新方法两层都须实现, 否则 wrapper 侧
        // AbstractMethodError(run64 首帧崩溃实锤)
        if (this.backend instanceof DhvkCommandEncoder probe) {
            probe.dhvkResetQueries(pool, first, count);
        }
    }

    @Override
    public long dhvkSubmitSemaphore() {
        return this.backend instanceof DhvkCommandEncoder probe ? probe.dhvkSubmitSemaphore() : 0L;
    }

    @Override
    public long dhvkCurrentSubmitIndex() {
        return this.backend instanceof DhvkCommandEncoder probe ? probe.dhvkCurrentSubmitIndex() : 0L;
    }
}
