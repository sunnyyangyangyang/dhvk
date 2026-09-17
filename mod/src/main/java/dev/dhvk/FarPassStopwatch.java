package dev.dhvk;

import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanQueryPool;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S2 步骤 1:far_terrain pass 的 GPU 秒表(规格 §2;s2 笔记 §1/§2)。
 *
 * <p>机制裁决(s2 笔记 §1,用户提案采纳):官方 26.2 帧同步面 =
 * {@code VulkanCommandEncoder} 的 timeline semaphore(每帧 submit 尾部信号
 * submitIndex)+ 官方公开 {@code writeTimestamp}/{@code createTimestampQueryPool}
 * —— 零新增机制:唯一新设备对象是一个官方工厂查询池(TIMESTAMP_EXT 64-bit)。
 *
 * <p>每帧:pass 录制前非阻塞读回在途帧结果({@code vkGetSemaphoreCounterValue}
 * ≥ 该帧写入时 submitIndex ⟹ 该帧 CBU GPU 全完 → {@code vkGetQueryPoolResults}
 * 64BIT 非阻塞);pass 录制中首尾夹一对时戳(slot 0/1,与官方 pass 同一 CBU);
 * 步骤 2 起扩 per-LOD 对价目表(槽位 init 预摊,R-c)。
 *
 * <p>因子开关 {@code DHVK_NOTICK=1}:不建池、不写时戳、不读回(pass 录制回到
 * S1 字节流)。关断纪律:池 = device 子对象,{@link #dispose()} 在
 * vkDestroyDevice 之前关闭(S0/S1 教训)。
 */
public final class FarPassStopwatch {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarPassStopwatch.class);

    /** 查询池槽数:步骤 1 用 pass 对(slot 0/1);步骤 2 起 4 个 LOD 对价目槽
     *  一步预摊 —— R-c 式"表不重建":池只建一次。 */
    public static final int SLOT_COUNT = 10;
    /** far pass 预算门(步骤 1~4,规格 §1);步骤 5(10km)抬至 2000。 */
    public static final long BUDGET_US = 1000L;
    /** 日志节流:每 N 个读回帧一行(另:首结果一次性行 + 40% 相对漂移行)。 */
    private static final int LOG_EVERY_N = 120;

    private static GpuQueryPool pool;
    private static long poolHandle = 0L;
    /** 时戳 → 微秒:**自校准** —— 连续两帧 pass 起始时戳差 ÷ 对应 CPU 真实时间差
     *  (NVIDIA 5090 驱动 timestampPeriod=1/validBits=64 均为名义值,vulkaninfo 对拍证实,
     *  不可用;CPU 时基锚定后与驱动自报值无关)。 */
    private static double tickToUs = 0.0;
    /** 设备自报 period(秒/时戳),仅 armed 日志存档。 */
    private static double reportedPeriodSec = -1.0;
    private static boolean calibrated = false;
    private static long calibPrevStartTick = 0L;
    private static long calibPrevCpuNanos = 0L;
    /** 在途帧环(官方 MAX_SUBMITS_IN_FLIGHT=2, 奇偶双槽):写入时 submitIndex +
     *  起始槽位 + 写入时刻 CPU 时基(自校准锚点, 零新增结构)。读回即失效(-1);
     *  奇偶覆盖 = 采样缺失, 非失败(遥测是采样器)。 */
    private static final long[] REG_SUBMIT_INDEX = new long[] {-1L, -1L};
    private static final int[] REG_SLOT = new int[2];
    private static final long[] REG_CPU_NANOS = new long[2];
    private static int regCount = 0;
    private static long lastPassUs = -1L;
    private static long lastLoggedUs = -1L;
    private static long readBackFrames = 0L;
    private static boolean firstResultLogged = false;
    private static boolean disposed = false;

    private FarPassStopwatch() {
    }

    /** 因子开关:DHVK_NOTICK=1 → 秒表全关(pass 录制与 S1 一致)。默认开。 */
    public static boolean envOn() {
        return !"1".equals(System.getenv("DHVK_NOTICK"));
    }

    /**
     * mod init(硬门槛后,设备就绪):官方工厂建 TIMESTAMP_EXT 64-bit 查询池
     * + dump timestampPeriod(时戳换算系数)。幂等。
     */
    public static synchronized void ensureCreated() {
        if (pool != null || disposed || !envOn()) {
            return;
        }
        if (VkHandles.deviceWrapper == null || VkHandles.pdevWrapper == null) {
            LOGGER.warn("[dhvk] farpass stopwatch skipped: handles not captured (first frame race)");
            return;
        }
        // 26.2:RenderSystem.getDevice() 返回 systems.GpuDevice wrapper(VulkanDevice 是 backend);
        // wrapper 的 createTimestampQueryPool 直通 backend → 实例即官方 VulkanQueryPool
        // (裸句柄经 DhvkQueryPool 探针)。gate 已过 ⟹ 后端必为 Vulkan,守卫只做类型确认。
        GpuQueryPool created = RenderSystem.getDevice().createTimestampQueryPool(SLOT_COUNT);
        if (!(created instanceof VulkanQueryPool)) {
            LOGGER.info("[dhvk] farpass stopwatch skipped: query pool is not the official Vulkan impl");
            return;
        }
        pool = created;
        poolHandle = ((DhvkQueryPool) (Object) created).dhvkHandle();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(VkHandles.pdevWrapper, props);
            // 设备自报值只存档:vulkaninfo 对拍证实 NVIDIA 5090 驱动 timestampPeriod=1、
            // qfam timestampValidBits=64 均为名义值(2026 registry 中 validBits 已移至
            // VkQueueFamilyProperties)→ 时戳频率走自校准,不消费自报值。
            float periodSec = props.limits().timestampPeriod();
            reportedPeriodSec = periodSec;
            int qfamValidBits = -1;
            try {
                int[] count = {1};
                VkQueueFamilyProperties.Buffer qfam = VkQueueFamilyProperties.calloc(1, stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(VkHandles.pdevWrapper, count, qfam);
                qfamValidBits = qfam.get(0).timestampValidBits();
            } catch (Throwable t) {
                LOGGER.warn("[dhvk] farpass stopwatch: queue family props query failed: {}", t);
            }
            LOGGER.info("[dhvk] farpass stopwatch armed: pool={} slots (pass pair + 4 LOD pairs pre-allocated), "
                            + "reported timestampPeriod={} s/tick, qfam0 timestampValidBits={} "
                            + "(driver values archived; tick rate self-calibrated from frame anchors)",
                    SLOT_COUNT, periodSec, qfamValidBits);
        }
    }

    /**
     * 每帧(render 回调,pass 录制前,零 CBU):读回已完成的在途帧的 far pass
     * 代价。非阻塞:GPU 侧 timeline counter 值 ≥ 该帧写入时 submitIndex
     * ⟹ 该帧 CBU 已全完 → 结果可读;未 ready 的留在环里下帧再试。
     */
    public static void readBack(DhvkCommandEncoder encoder) {
        if (pool == null || !envOn() || regCount == 0) {
            return;
        }
        long semaphore = encoder.dhvkSubmitSemaphore();
        if (semaphore == 0L) {
            return;
        }
        long counter;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer counterBuf = stack.callocLong(1);
            int r = VK12.vkGetSemaphoreCounterValue(VkHandles.deviceWrapper, semaphore, counterBuf);
            if (r != 0) {
                LOGGER.warn("[dhvk] farpass stopwatch: vkGetSemaphoreCounterValue = {}", r);
                return;
            }
            counter = counterBuf.get(0);
        }
        int newest = (regCount - 1) & 1;
        int valid = Math.min(regCount, 2);
        for (int k = 0; k < valid; k++) {
            int i = (newest - k + 2) & 1;
            long submitIndex = REG_SUBMIT_INDEX[i];
            if (submitIndex < 0L || counter < submitIndex) {
                continue;   // 已读回 / GPU 侧未完成
            }
            int slot = REG_SLOT[i];   // run62 起存查询对基址(偶数, = 帧奇偶×2)
            long cpuNanos = REG_CPU_NANOS[i];
            long start = readQuery(slot);
            long end = readQuery(slot + 1);
            REG_SUBMIT_INDEX[i] = -1L;   // 读回即失效(防幻影锚点重放)
            if (start <= 0L || end <= 0L || end < start) {
                LOGGER.info("[dhvk] farpass telemetry: pass=unreadable (start={}, end={}, slot={})",
                        start, end, slot);
                continue;
            }
            calibrate(start, cpuNanos);
            // 校准前报原始 ticks(无预算判定),校准后转微秒
            lastPassUs = calibrated ? Math.round((end - start) * tickToUs) : (end - start);
            logTelemetry();
        }
        readBackFrames++;
    }

    /** pass 开始(createRenderPass 后):写起始时戳 + 登记本帧在途项。 */
    public static void emitStart(DhvkCommandEncoder encoder) {
        emit(encoder, 0, true);
    }

    /** pass 结束(最后一次 draw 后):写终止时戳(与起始同一 submit,不再登记)。 */
    public static void emitEnd(DhvkCommandEncoder encoder) {
        emit(encoder, 1, false);
    }

    /** 当前帧查询对基址(偶数):run62/64 修 —— 在途帧各用独立查询对(帧奇偶×2, 4 查询窗口),
     *  帧 N 的读回不会再撞上帧 N+1 的重置/覆写; 同一对 2 帧后才复用, 且 CBU 侧重置
     *  (resetFrameQueries, pass 外)按提交序在同队列上晚于帧 N 的写入执行, 无竞态。 */
    private static int curSlotBase = 0;

    /** run64: 重置本帧查询对 —— CBU 侧 vkCmdResetQueryPool 必须于 render pass 实例外
     *  发行(VUID-vkCmdResetQueryPool-commandBuffer-00001, run63 VVL 实锤); 调用点 =
     *  createRenderPass 之前(CBU 已在录制, 尚无 render pass)。与两帧前同对的 GPU 写入
     *  按提交序串行 → 消除 run62 "query not reset + present 脱钩" 崩溃链。 */
    public static void resetFrameQueries(DhvkCommandEncoder encoder) {
        if (pool == null || !envOn()) {
            return;
        }
        curSlotBase = (regCount & 1) * 2;
        encoder.dhvkResetQueries(((DhvkQueryPool) (Object) pool).dhvkHandle(), curSlotBase, 2);
    }

    private static void emit(DhvkCommandEncoder encoder, int slot, boolean register) {
        if (pool == null || !envOn()) {
            return;
        }
        long semaphore = encoder.dhvkSubmitSemaphore();
        long submitIndex = encoder.dhvkCurrentSubmitIndex();
        if (semaphore == 0L || submitIndex == 0L) {
            return;
        }
        encoder.dhvkWriteTimestamp(pool, curSlotBase + slot);
        if (register) {
            // 奇偶双槽(官方在途上限):奇偶覆盖 = 采样缺失,非失败(遥测是采样器)
            int i = regCount & 1;
            REG_SUBMIT_INDEX[i] = submitIndex;
            REG_SLOT[i] = curSlotBase;
            REG_CPU_NANOS[i] = System.nanoTime();
            regCount++;
        }
    }

    private static long readQuery(int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.callocLong(1);
            int r = VK10.vkGetQueryPoolResults(VkHandles.deviceWrapper, poolHandle, slot, 1, out, 8L,
                    VK10.VK_QUERY_RESULT_64_BIT);
            if (r != 0) {
                LOGGER.warn("[dhvk] farpass stopwatch: vkGetQueryPoolResults(slot={}) = {}", slot, r);
                return -1L;
            }
            return out.get(0);
        }
    }


    /** 自校准:相邻两帧 pass 起始时戳差 ÷ 对应 CPU 真实时间差 = 真实 tick 频率。
     *  首对有效基线(>50 ticks 且 >10ms)一次性标定;GPU 时戳频率与提频无关,标定后恒定。 */
    private static void calibrate(long startTick, long cpuNanos) {
        if (calibrated || startTick <= 0L || cpuNanos <= 0L) {
            return;
        }
        if (calibPrevStartTick <= 0L) {
            calibPrevStartTick = startTick;
            calibPrevCpuNanos = cpuNanos;
            return;
        }
        long dtTicks = startTick - calibPrevStartTick;
        double dtSec = (cpuNanos - calibPrevCpuNanos) / 1e9;
        if (dtTicks > 50L && dtSec > 0.01) {
            tickToUs = 1e6 * dtSec / dtTicks;
            calibrated = true;
            LOGGER.info("[dhvk] farpass stopwatch calibrated: {} us/tick ({} ticks over {} ms; "
                            + "device-reported period={} s/tick rejected)",
                    String.format("%.4f", tickToUs), dtTicks, Math.round(dtSec * 1000), reportedPeriodSec);
        }
        // 基线区间不足(高帧率):保留最旧锚点不推进 —— 区间自然拉宽后比值仍线性成立
    }

    /** 日志节流:首结果一次性行 + 每 120 读回帧 + 40% 相对漂移;超预算加 BUDGET-BREACH 标记。
     *  校准前报原始 ticks(不做预算判定)。 */
    private static void logTelemetry() {
        if (!calibrated) {
            LOGGER.info("[dhvk] farpass telemetry: pass={} ticks (uncalibrated) frame={}",
                    lastPassUs, readBackFrames);
            return;
        }
        boolean first = !firstResultLogged;
        boolean periodic = readBackFrames % LOG_EVERY_N == 0L;
        boolean drift = lastLoggedUs > 0L && Math.abs(lastPassUs - lastLoggedUs) * 5L > lastLoggedUs * 2L;
        if (!first && !periodic && !drift) {
            return;
        }
        firstResultLogged = true;
        lastLoggedUs = lastPassUs;
        String breach = lastPassUs > BUDGET_US ? " BUDGET-BREACH(>" + BUDGET_US + "us)" : "";
        LOGGER.info("[dhvk] farpass telemetry: pass={} us (budget={} us){} frame={}",
                lastPassUs, BUDGET_US, breach, readBackFrames);
    }

    /** 关断纪律:查询池 = device 子对象,vkDestroyDevice 之前关闭。幂等。 */
    public static synchronized void dispose() {
        if (pool == null) {
            return;
        }
        ((VulkanQueryPool) pool).close();
        pool = null;
        poolHandle = 0L;
        regCount = 0;
        REG_SUBMIT_INDEX[0] = -1L;
        REG_SUBMIT_INDEX[1] = -1L;
        calibrated = false;
        calibPrevStartTick = 0L;
        calibPrevCpuNanos = 0L;
        disposed = true;
        LOGGER.info("[dhvk] farpass stopwatch disposed (query pool closed)");
    }
}
