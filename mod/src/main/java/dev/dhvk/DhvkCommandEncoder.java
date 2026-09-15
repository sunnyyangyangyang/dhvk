package dev.dhvk;

/**
 * S1 任务 2:命令编码器探针的编译期可见面。
 *
 * <p>两个探针 mixin 分别把实现注入官方 wrapper({@code systems.CommandEncoder})与
 * Vulkan 后端({@code vulkan.VulkanCommandEncoder});渲染器只依赖本接口,
 * 保持"不引用 Mojang 内部包装类细节"的架构原则(笔记 §架构)。
 */
public interface DhvkCommandEncoder {

    /** 当前录制中的 CBU 裸句柄(0 = encoder 空闲, 无活动命令缓冲)。 */
    long dhvkCurrentCbu();
}
