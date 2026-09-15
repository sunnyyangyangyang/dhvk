package dev.dhvk.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
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
 */
@Mixin(com.mojang.blaze3d.systems.CommandEncoder.class)
public abstract class CommandEncoderDhvkProbeMixin implements DhvkCommandEncoder {

    @Shadow
    private CommandEncoderBackend backend;

    @Override
    public long dhvkCurrentCbu() {
        return this.backend instanceof DhvkCommandEncoder probe ? probe.dhvkCurrentCbu() : 0L;
    }
}
