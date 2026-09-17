package dev.dhvk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * run111: 裸 vkCmdUpdateBuffer 上传通道 —— 完全绕开官方瞬态 ring。
 *
 * <p>run110 判罪矩阵 (C 干净 / A,B,D,E 全炸, E=纯缓冲 ring staging 也炸): 官方
 * createBuffer(ByteBuffer) 内部走 uploadStaging → MappedView memCopy,
 * 帧首窗口对瞬态 ring 的 staging 污染其记账, 官方自身每帧 multiUpload 持脏
 * byte[] 引用 SEGV (jbyte_disjoint_arraycopy, si_addr=0x10)。本类以专属 command
 * pool + 一次性 CBU + 复用 fence 在**同一 graphics queue** 上提交 (与官方 CBU
 * 同队列 FIFO 排序 ⇒ 数据恒在场景读取前就绪), 与 ring 零接触。
 * 提交走官方同款 vkQueueSubmit2KHR (LWJGL 3.4.1 此 fork 的 VkSubmitInfo 无 count setter,
 * *2 结构体计数由指针隐式携带, 与官方 VulkanQueue 惯用法一致)。
 *
 * <p>限制: vkCmdUpdateBuffer 单次 ≤64KB —— 本项目全部几何缓冲 (S0/信标/扇/带 mesh)
 * 均远小于该上限; 若未来带 mesh 超 64KB 需分段或回退。
 */
public final class DhVkRawUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DhVkRawUploader.class);

    private static long pool;
    private static long fence;
    private static volatile boolean ready;
    // run119: C2 liveness 钉子 — 直接缓冲的 native 内存只能靠 Java 对象强引用钉住;
    // C2 判定参数在 JNI 调用后已死 → 调用中途 GC → Cleaner 释放 native 内存 → 驱动
    // memmove 悬空指针 SEGV (run118: __memmove_avx512, si_addr=0x10, RDX=64=扇VBO大小)。
    private static final Object[] PIN = new Object[1];

    private DhVkRawUploader() {
    }

    /** 设备就绪窗调用: 建专属 pool + 复用 fence (幂等; 句柄未捕获时静默跳过, 下帧再试)。 */
    public static void ensureReady() {
        if (ready || VkHandles.deviceWrapper == null) {
            return;
        }
        synchronized (DhVkRawUploader.class) {
            if (ready || VkHandles.deviceWrapper == null) {
                return;
            }
            // 注意: 栈上分配的 struct 在本 LWJGL fork 里不可 close() (会误走 nmemFree → jemalloc SEGV),
            // 官方惯用法 = 只开 MemoryStack, 结构体随栈弹出释放。
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final LongBuffer poolPtr = stack.mallocLong(1);
                final LongBuffer fencePtr = stack.mallocLong(1);
                final VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                        .queueFamilyIndex(0);
                check(VK10.vkCreateCommandPool(VkHandles.deviceWrapper, pci, null, poolPtr),
                        "vkCreateCommandPool");
                pool = poolPtr.get(0);
                final VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
                check(VK10.vkCreateFence(VkHandles.deviceWrapper, fci, null, fencePtr), "vkCreateFence");
                fence = fencePtr.get(0);
                ready = true;
                LOGGER.info("[dhvk] raw uploader ready: pool=0x{} fence=0x{} (ring-free upload path)",
                        Long.toHexString(pool), Long.toHexString(fence));
            }
        }
    }

    /** 是否就绪 (设备 wrapper 已捕获且专属 pool 已建)。 */
    public static boolean isReady() {
        return ready;
    }

    /**
     * 以裸 vkCmdUpdateBuffer 把 data (用 remaining) 写入持久缓冲。
     * 专属一次性 CBU 经 vkQueueSubmit2KHR 提交到官方同一 graphics queue 并等 fence
     * ⇒ 调用返回即数据已上卡, 且同队列 FIFO 保证场景读取前就绪。
     */
    /** vkCmdUpdateBuffer 单次拷贝上限 (VUID-vkCmdUpdateBuffer-dataSize: <= 65536, 且为 4 的倍数)。 */
    private static final int UPDATE_BUF_MAX = 65536;

    public static void upload(final GpuBuffer buffer, final ByteBuffer data) {
        if (!ready) {
            throw new IllegalStateException("[dhvk] raw uploader not ready (device wrapper not captured?)");
        }
        final long vkBuffer = ((VulkanGpuBuffer) buffer).vkBuffer();
        // run120: 本 LWJGL fork 的 MemoryUtil.memAddress 对 JDK 直接/堆缓冲恒 0 →
        // 源头一律先拷进 LWJGL 自管缓冲 (带元数据, 地址必真; 其 slice 亦保留地址)。
        final int size = data.remaining();
        final ByteBuffer nativeData = MemoryUtil.memAlloc(size);
        nativeData.put(data);
        nativeData.flip();
        // run135: >64KB 走 staging — 分块 updateBuffer 灌临时缓冲, 再 vkCmdCopyBuffer 整块搬。
        // (run132 VVL 实锤: dataSize=3234848 > 65536 → 带 VBO/IBO 只上了头 64KB, 渲染碎掉)
        final GpuBuffer staging = size > UPDATE_BUF_MAX
                ? com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(
                        () -> "dhvk/stage", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, (long) size)
                : null;
        PIN[0] = nativeData;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer cbufArr = stack.mallocPointer(1);
            final VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            check(VK10.vkAllocateCommandBuffers(VkHandles.deviceWrapper, cai, cbufArr),
                    "vkAllocateCommandBuffers");
            final VkCommandBuffer cbuf = new VkCommandBuffer(cbufArr.get(0), VkHandles.deviceWrapper);
            try {
                final VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                check(VK10.vkBeginCommandBuffer(cbuf, beginInfo), "vkBeginCommandBuffer");
                if (size <= UPDATE_BUF_MAX) {
                    VK12.vkCmdUpdateBuffer(cbuf, vkBuffer, 0L, nativeData);
                } else {
                    final long stageVk = ((VulkanGpuBuffer) staging).vkBuffer();
                    long off = 0;
                    while (off < size) {
                        final int len = (int) Math.min((long) UPDATE_BUF_MAX, size - off) & ~3;
                        if (len <= 0) {
                            break;
                        }
                        VK12.vkCmdUpdateBuffer(cbuf, stageVk, off, nativeData.slice((int) off, len));
                        off += len;
                    }
                    // fork 绑定: vkCmdCopyBuffer(cbuf, srcBuffer, srcOffset, VkBufferCopy.Buffer)
                    // fork 的 VkBufferCopy 无 sType 字段 (仅 srcOffset/dstOffset/size)
                    final org.lwjgl.vulkan.VkBufferCopy.Buffer bcopy = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack);
                    bcopy.dstOffset(0L);
                    bcopy.size(size & ~3L);
                    VK10.vkCmdCopyBuffer(cbuf, stageVk, 0L, bcopy);
                }
                check(VK10.vkEndCommandBuffer(cbuf), "vkEndCommandBuffer");
                check(VK10.vkResetFences(VkHandles.deviceWrapper, fence), "vkResetFences");
                final VkQueue queue = new VkQueue(VkHandles.queue, VkHandles.deviceWrapper);
                final VkSubmitInfo2.Buffer submits = VkSubmitInfo2.calloc(1, stack);
                submits.sType$Default();
                final VkCommandBufferSubmitInfo.Buffer infos = VkCommandBufferSubmitInfo.calloc(1, stack);
                submits.pCommandBufferInfos(infos);
                infos.sType$Default();
                infos.commandBuffer(cbuf);
                check(KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence), "vkQueueSubmit2KHR");
                check(VK10.vkWaitForFences(VkHandles.deviceWrapper, fence, true, 5_000_000_000L),
                        "vkWaitForFences");
            } finally {
                VK10.vkFreeCommandBuffers(VkHandles.deviceWrapper, pool, cbuf);
            }
        } finally {
            PIN[0] = null;
            if (staging != null) {
                staging.close();
            }
        }
    }

    private static void check(final int rc, final String op) {
        if (rc != VK10.VK_SUCCESS) {
            throw new IllegalStateException("[dhvk] " + op + " failed: rc=" + rc);
        }
    }
}
