# S2 读码笔记（任务 0 产出，步骤1 秒表；与代码冲突时以本笔记为准）

- 日期：2026-09-15（步骤1 勘察收口；步骤 2~5 先决随步回填）
- 依据：mc-src/client/ 26.2 反混淆树 + mc-src/artifacts（client.jar）+ refs/vulkan-headers
  1.4.357 + 随包 LWJGL 3.4.1（javap/unzip 实证）+ S1 笔记（继承事实）

## §1 官方帧同步面（步骤1 裁决依据，全部实测行号）

1. **fenceless 提交**：`VulkanQueue.java:124` `KHRSynchronization2.vkQueueSubmit2KHR(
   queue, submits, 0L)`（无 binary fence；Submission stage 可携带 timeline
   wait/signal 操作，`SemaphoreOp(long vkSemaphore, long value, long stageMask)`）。
2. **timeline semaphore 每帧信号**：`VulkanCommandEncoder` L52
   `private final long submitSemaphore`（L68-77 以 `VkSemaphoreTypeCreateInfo`
   创建 timeline 型）；L198 `this.signalSemaphore(this.submitSemaphore,
   this.currentSubmitIndex, 65536L)` —— **每帧 submit 尾部以该帧
   submitIndex 为值信号 timeline**（stage mask 0x10000）。
3. **官方读回面**：`awaitSubmitCompletion(submitIndex, timeoutNS)`（L584-611）
   = `VK12.vkWaitSemaphores`（L601，poll timeline 值）→ 推进
   `completedSubmitIndex`（**仅当官方代码经 GpuFence.awaitCompletion await 时
   才推进**，L614-633）→ 我方读回安全性**不依赖**它；
   **裁决（用户提案采纳）**：我方每帧用
   `VK12.vkGetSemaphoreCounterValue(device, submitSemaphore)`（GPU 侧实时值，
   零阻塞）≥ 本帧写入时的 currentSubmitIndex ⟹ 该帧 CBU（含 far pass）GPU
   全完 → 非阻塞 `vkGetQueryPoolResults(..., 0, VK_QUERY_RESULT_64_BIT)` 读回。
4. **官方时间戳写路径（公开、VVL 实证过）**：`VulkanCommandEncoder.
   writeTimestamp(GpuQueryPool, int)`（L636-640）= `vkResetQueryPool` +
   `KHRSynchronization2.vkCmdWriteTimestamp2KHR(cbu, 65536L, pool, i)`；
   官方 `getTimestampNow()`（L642-663）同款调用（transient CBU + **阻塞式**
   `vkGetQueryPoolResults(..., 0, 3)`（WAIT|64BIT）——一次性探测可阻塞，
   每帧遥测必须非阻塞（flags=2）；
   `VulkanDevice.createTimestampQueryPool(int)` **public**（L323）→
   `VulkanQueryPool`（L15-22：`queryType(2)` = TIMESTAMP_EXT、64-bit；
   implements Destroyable → close 释放 device 子对象，dispose 纪律直接覆盖）；
   `VulkanDevice.getTimestampNow()`（L328）公开。
5. **LWJGL 3.4.1 类面（unzip 实证随包）**：VK12（vkGetSemaphoreCounterValue /
   vkGetQueryPoolResults / vkWaitSemaphores / vkCreateSemaphore /
   vkDestroySemaphore）、KHRSynchronization2（vkCmdWriteTimestamp2KHR /
   vkQueueSubmit2KHR——官方代码已在用）、KHRShaderClock +
   VkPhysicalDeviceShaderClockFeaturesKHR（备用升级路径，步骤1 不用）。

## §2 步骤1 实施面（写码前已核实；余下 30 秒级小项写码时顺带销账）

1. **探针扩展**：`DhvkCommandEncoder` 接口加
   `dhvkWriteTimestamp(GpuQueryPool, int)` / `dhvkSubmitSemaphore()` /
   `dhvkCurrentSubmitIndex()`；`VulkanCommandEncoderProbeMixin` @Shadow
   `submitSemaphore`（final long，L52 实证存在）+ `currentSubmitIndex`
   （L589/598 实证被引用，**声明行写码前 grep 确认类型**），writeTimestamp
   委托官方公开方法（mixin this = VulkanCommandEncoder 实例）。
2. **发射点不变**：FarTerrainRenderer L478 区（`((DhvkCommandEncoder)(Object)
   encoder).dhvkCurrentCbu()`）同一时机加 timestamp 写入；pass 总夹一对
   （slot 0/1）；步骤2 起 per-LOD 组夹对（slot 预摊，pool N=10 一步到位）。
3. **读回点**：帧回调内、pass 录制前（零 CBU）：counter 判 ready →
   非阻塞读结果 → µs = (end−start) × 2^(−timestampValidBits) × 1e6
   （device property `timestampValidBits`，init dump 进日志——写码时
   经捕获的 pdev 一次查询，VkHandles 已有 pdev 字段）。
4. **新类**：`FarPassStopwatch`（pool 生命周期 / emit / readBack /
   日志节流[每120帧+档位变化] / 预算门 1ms→步骤5 2ms / DHVK_NOTICK /
   dispose 注册）；`FarTerrainRenderer` init 建池、render 回调 emit+readBack、
   dispose 关池（vkDestroyDevice 之前）。
5. **VVL 预期**：全部调用 = 官方代码自身行为（getTimestampNow 同源）→
   双零为期望值；异常形态与回填 = 规格 §7 R1/R2。

## §3 步骤5 勘察半成品（本轮副产品，落点已收窄）

1. **投影**：`GameRenderer.renderLevel` L532 `new Matrix4f(
   cameraState.projectionMatrix)`（含 bob L539、旋转/缩放效果 L544-552，
   均在矩阵上后乘）→ L554 `RenderSystem.setProjectionMatrix(
   this.levelProjectionMatrixBuffer.getBuffer(projectionMatrix),
   ProjectionType.PERSPECTIVE)`（static，RenderSystem.java L177）。
   hook 候选：① cameraState.projectionMatrix 构建点（Camera 侧，**待读**）；
   ② static setProjectionMatrix slice 重写兜底（矩阵解析 near/fov → 新 far
   重算 m[10]/m[2][3]/m[3][3] 写回）。
2. **fog**：`GameRenderer.renderLevel` L556 `fogRenderer.updateBuffer(
   cameraState.fogData)` → L557 `getBuffer(FogRenderer.FogMode.WORLD)` →
   `RenderSystem.java:281` `renderPass.setUniform("Fog", fog)`（per-pass
   push；BGL = `BindGroupLayouts.FOG`，BindGroupLayouts.java L15）。
   hook = `FogRenderer.updateBuffer` 单点（**待读**：确认是 Fog 块 float
   写入的单点）；覆写 FogRenderDistanceEnd@28 / FogSkyEnd@32 /
   FogCloudsEnd@36 = bandFar（偏移按 fog.glsl S0 已核实布局，写码前复核）。

## §4 步骤4 勘察半成品（本轮副产品）

1. **LWJGL 类面随包（unzip 实证）**：`KHRFragmentShadingRate`、
   `VkRenderingFragmentShadingRateAttachmentInfoKHR`（**dynamic rendering
   的 rate image 附件**，pNext 挂 VkRenderingInfo——26.2 = dynamic rendering，
   S0 实证）、`VkPipelineFragmentShadingRateStateCreateInfoKHR`（S1 固定率
   结构）、`VkPhysicalDeviceFragmentShadingRate{Features,Properties}KHR`、
   `NVFragmentShadingRateEnums` + `...PropertiesNV`（**8×8 rate 集查询
   候选**）、`KHRShaderClock` 族。
2. **待读（步骤4 任务0）**：官方 createRenderPass → `vkCmdBeginRendering`
   的 VkRenderingInfo 构建处（有无 pNext 注入点，规格 R7 退路判据）；
   rate image 规范文本（format/layout/usage + 与固定率的组合语义）；
   5090 FSR properties 运行时 dump（supported rate 集 / 8×8？）。

## §5 风险位（写码/首跑销账）

- R1（timeline 值滞后）：读回判据备选 = completedSubmitIndex 影子 /
  自建小 binary fence（读回层与写时戳层解耦）；
- R2（stage 0x10000 被点名）：按 VVL 报错回填；
- 探针 @Shadow 私有字段（submitSemaphore 为 final——mixin @Shadow final
  只读字段 OK，S0 先例）；
- `GpuQueryPool` 接口 vs `VulkanQueryPool` 具体类的类型面（接口有
  writeTimestamp？——官方 writeTimestamp 收 GpuQueryPool 参数，直接传）。
