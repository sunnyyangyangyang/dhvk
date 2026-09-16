package dev.dhvk;

/**
 * S2 步骤 1:官方查询池探针的编译期可见面。
 *
 * <p>官方 {@code VulkanQueryPool.vkQueryPool()} 是 protected(mod 侧不可见)
 * → 经 mixin {@code @Shadow} 字段桥接,渲染器只依赖本接口。
 */
public interface DhvkQueryPool {

    /** 裸 {@code VkQueryPool} 句柄。 */
    long dhvkHandle();
}
