package dev.dhvk;

import com.mojang.blaze3d.systems.GpuQueryPool;

/**
 * S1 任务 2:命令编码器探针的编译期可见面。
 *
 * <p>两个探针 mixin 分别把实现注入官方 wrapper({@code systems.CommandEncoder})与
 * Vulkan 后端({@code vulkan.VulkanCommandEncoder});渲染器只依赖本接口,
 * 保持"不引用 Mojang 内部包装类细节"的架构原则(笔记 §架构)。
 *
 * <p>S2 步骤 1:加 GPU 秒表面(官方 writeTimestamp / timeline semaphore /
 * submitIndex,s2 笔记 §1/§2)。
 */
public interface DhvkCommandEncoder {

    /** 当前录制中的 CBU 裸句柄(0 = encoder 空闲, 无活动命令缓冲)。 */
    long dhvkCurrentCbu();

    /** 在当前录制中的 CBU 写一个 GPU 时间戳(委托官方公开 writeTimestamp, s2 笔记 §1.4)。 */
    void dhvkWriteTimestamp(GpuQueryPool pool, int slot);

    /** 官方 timeline semaphore 裸句柄(每帧 submit 尾部以本帧 submitIndex 信号; 0 = 非 Vulkan 后端)。 */
    long dhvkSubmitSemaphore();

    /** 本帧的 submitIndex(= 本帧 submit 将信号的值, 录制期间即最终值, s2 笔记 §1.2/§1.6)。 */
    long dhvkCurrentSubmitIndex();
}
