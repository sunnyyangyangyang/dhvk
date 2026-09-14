package dev.dhvk;

/**
 * 26.2 官方渲染器实际在用的 Vulkan 原生句柄持有者。
 *
 * <p>架构原则(spec §7-3):mod 侧只依赖 native 句柄(LWJGL 地址),
 * 不引用 Mojang 内部包装类,防小版本更新重构包装类导致 mod 全线报红。
 */
public final class VkHandles {

    public static long device = 0L;
    public static long queue = 0L;
    public static long commandPool = 0L;
    public static boolean captured = false;

    private VkHandles() {
    }

    public static void capture(long deviceHandle, long queueHandle, long commandPoolHandle) {
        device = deviceHandle;
        queue = queueHandle;
        commandPool = commandPoolHandle;
        captured = true;
    }

    public static String format() {
        if (!captured) {
            return "not captured yet (probe mixin not fired)";
        }
        return String.format("device=0x%x queue=0x%x pool=0x%x", device, queue, commandPool);
    }
}
