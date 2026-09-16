# S2 步骤 1 验收:far_terrain pass GPU 秒表

**状态:✅ 闸门闭合(标准档 VVL 零 + 全开档 VVL 零 + 遥测可信 + 目视无回归 + 干净退出)**
**日期:2026-09-15 深夜 | 机器:RTX 5090 (driver 615.71.9.0) | MC 26.2 + Vulkan | VVL 1.4.341**

## 验收物(规格 §1 步骤 1 门)

1. **GPU 秒表全链通**:官方工厂 TIMESTAMP_EXT 64-bit 查询池(唯一新设备对象)
   + 官方公开 writeTimestamp 首尾夹时戳 + timeline semaphore 非阻塞读回。
2. **遥测可信**:自校准换算(见下),far pass GPU 实耗拍实。
3. **VVL 双零**:标准档(run3)零验证行;全开档(run4,同步+GPU协助+最佳实践,
   与 S1 run41c 同程序)零验证行。
4. **目视**:用户裁定"还是 S1 那张脸"(run2);run3/run4 同程序无新伪影。
5. **干净退出**:descriptor heap 正常关闭,exit 0。

## 遥测结果(run3 标准档 / run4 全开档)

- **时戳频率自校准:≈1.0-1.1 GHz/tick**(run3: 0.0009 us/tick, 5734 万 ticks/54ms;
  run4: 0.0010 us/tick, 2150 万 ticks/21ms —— 两档一致)。
- **far pass 实耗:6~21 µs**(run3 6-21µs, run4 6-18µs;双模态:
  偶数帧 ~7-9µs, 奇数帧 ~16-21µs —— 提交节奏双相,步骤 2 起深挖)。
- **预算门 1000µs:富余 50~120 倍,无 BUDGET-BREACH**。
- 步骤 2-4(LOD/遮挡/VRS)的削减目标 = 在这条基线之上防回涨 + per-LOD 价目表
  (查询池 10 槽已预摊:pass 对 + 4 个 LOD 对)。

## run 序列

| run | 档 | 事件 |
|---|---|---|
| run1 (20:42) | 标准 | 机制首通:armed+telemetry 全链;暴露驱动自报 period=1 名义值 |
| run2 (20:49) | 标准 | 自校准首版;幻影锚点缺陷(见下)→ 换算放大 1000x;目视裁定"还是 S1 那张脸";run42 stream 尖峰 2972µs@frame82 = S1 笔记 1268-1270 已备案形态 |
| run3 (20:54) | 标准 | 奇偶环+最旧锚点校准修复后:校准 0.0009us/tick,telemetry 6-21µs;VVL 0;stream 孤峰 1511µs@frame2808(同备案形态);干净退出 |
| run4 (20:57) | 全开档 | 同步+GPU协助+最佳实践三件套(与 S1 run41c 同程序):VVL 0;校准 0.0010us/tick;telemetry 6-18µs;干净退出 |

日志:logs/s2-run-step1-204238.log / s2-run2-step1-204901.log /
s2-run3-step1-205416.log / s2-run4-step1-full-205748.log

## 关键裁决与偏差(相对规格 §2 设计)

1. **驱动自报时戳参数不可信(NVIDIA 5090)**:vulkaninfo 对拍证实
   limits.timestampPeriod=1、qfam timestampValidBits=64 均为名义值
   (AMD 的 2^(-validBits) 周期公式在 NVIDIA 上不适用)→
   **偏差:规格 §2 的 2^(-validBits)×1e6 换算改为自校准**
   (连续两帧 pass 起始时戳差 ÷ 对应 CPU 真实时间差;锚点记在既有在途帧环,
   零新增机制;区间不足时保留最旧锚点等区间自然拉宽)。
2. **26.2 wrapper/backend 分面**:RenderSystem.getDevice() 返回 systems.GpuDevice
   wrapper(VulkanDevice 是 backend),createTimestampQueryPool 直通官方
   VulkanQueryPool;encoder 同理(systems.CommandEncoder 探针 mixin 委托 backend)。
3. **LWJGL 3.4.1(2026 registry)**:struct 全在 org.lwjgl.vulkan 根包;
   timestampValidBits 移至 VkQueueFamilyProperties。
4. **幻影锚点缺陷(run2 捕获,已修)**:移位帧环初始态产生 submitIndex=0 的
   幽灵条目 → 帧 0 双读、cpu=0(开机时基)陈旧锚点混入校准基线 → 1000x 错位。
   修复 = 奇偶双槽 + 读回即失效(-1) + 校准保留最旧锚点。
5. **stream 预算孤峰**:run42 闸门(S1 遗产)在 run2/run3 各报一次
   2972µs/1511µs 孤立尖峰 = S1 笔记 1268-1270 已备案形态(官方瞬态环一次性
   成本/偶发渲染线程颠簸),闸门只报告不兜底,非步骤 1 回归。

## 代码面(本次提交)

- FarPassStopwatch(新):官方工厂池 + timeline 非阻塞读回 + 奇偶在途环 +
  自校准 + DHVK_NOTICK 开关 + dispose 关池(device 子对象纪律)。
- DhvkCommandEncoder 接口 +3 面(writeTimestamp 委托/submitSemaphore/
  currentSubmitIndex);VulkanCommandEncoderProbeMixin/CommandEncoderDhvkProbeMixin
  扩展(官方 writeTimestamp 经 Object 双投调用);VulkanQueryPoolProbeMixin(新,
  @Shadow vkQueryPool 字段);dhvk.mixins.json +1。
- FarTerrainRenderer 接线 6 处(readBack 于 pass 前/emit 于 pass 内首尾/
  ensureCreated/dispose)。

