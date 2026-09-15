package dev.dhvk;

/**
 * dhvk 专属的 GpuBuffer.Usage 标记位(官方 usage 位止于 512 = USAGE_INDIRECT_PARAMETERS).
 */
public final class DeviceAddressUsage {

    /**
     * 声明该缓冲的内存必须设备可寻址: 经官方 createBuffer 时 usage 会叠上
     * VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT(VulkanConstBdaUsageMixin 手术, 2026 值空间
     * 无独立 BDA usage 位), VMA 随之以 DEVICE_ADDRESS 位分配内存 ——
     * vkGetBufferDeviceAddress 合法化三要素(usage 位 + 设备 feature + DEVICE_ADDRESS 内存)之一.
     */
    public static final int DEVICE_ADDRESS = 1024;

    private DeviceAddressUsage() {
    }
}
