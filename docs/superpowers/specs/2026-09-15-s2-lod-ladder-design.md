# S2 设计规格：LOD 阶梯 + GPU 遥测 + 遮挡剔除 + VRS rate map + 远平面三件套

- 日期：2026-09-15
- **v2（2026-09-16）pivot**：几何生产者转型 CPU 二进制贪心网格化（真贴图 + 烘焙 AO，高度场退役）——步骤序 / 红线 v2 / LOD 数据模型由 `docs/superpowers/specs/2026-09-16-greedy-mesh-pivot-design.md` 改写；本文件保留为 v1 历史 + v2 步骤 5/6/7（遮挡剔除 / VRS rate map / 远平面三件套）机制细节基线，v1 步骤1（秒表）已闭合继承为遥测基线，v1 步骤2（阶梯）由 v2 步骤2（体素版）改写。
- 状态：设计对话完成，用户批准（2026-09-15 晚，两项裁定）：
  - **执行顺序（用户原话："先装表，再造货，再省货，最后给省货定价"）**：
    步骤1 GPU 秒表 → 步骤2 LOD 阶梯 → 步骤3 遮挡剔除 → 步骤4 VRS rate map →
    步骤5 远平面三件套 → 10km 总闸门。每件独立一道闸门，过了才往下走，哪件都不做兜底；
  - **S2/S3 边界 = A**：远平面 hook + fog end 随 bandFar 扩展 + ndcz 重推导焊进 S2
    （步骤5）；S3 只留打磨（距离 alpha ramp、≥32km ModelOffset、地曲率可选项、
    10/20km 截图对比）。
- 依据：
  - 阶段二 MVP 总计划 `docs/superpowers/plans/2026-09-15-phase2-dh-geometry-hauler.md`
    （§4 S2 切片 / §4a VK2026 采用矩阵 / §4b DH 参照系 / S4 红线 R-a~R-f）
  - S1 规格 `docs/superpowers/specs/2026-09-14-s1-heap-carrier-design.md` +
    S1 计划 `docs/superpowers/plans/2026-09-14-s1-heap-carrier.md` +
    S1 读码笔记 `docs/s1-code-reading-notes.md`（事实来源；S2 笔记 `docs/s2-code-reading-notes.md` 同契约）
  - S1 闭合状态：commit `4bd9acc`（run45-50 目验裁决；"超远 -400 正面远观"与
    全带剪影视觉由 S2 承接）
  - API 基线：refs/vulkan-headers 1.4.357 + 游戏随包 LWJGL 3.4.1 类面（本规格引用前均已核实随包）
  - DH 参照系：refs/dh（FullDataOcclusionCuller / LodUtil / DhFrustumBounds 读码摘录见总计划 §4b）

## 0. 范围与非目标

**目标**：距离带拉到 10km+ 仍稳；per-LOD 预算由 GPU 侧遥测驱动（不再手调）；
VRS 按屏幕距离定价；per-cell UBO 并入 heap、官方动态通道退役为 push 壳。

**继承（S1 资产，不动）**：设备手术 + 硬门槛（不兜底）/ heap 载体（每帧一次
`vkCmdBindResourceHeapEXT` 绑整表）/ GeometryArena / CellRegistry（几何唯一权威）/
raw SSBO block tile（R-b 已同步上传）/ VRS 设备手术（`VK_KHR_fragment_shading_rate`，
S1 为 pass 级固定 2×2，步骤4 升级为 rate image）/ 探针三件套 / dispose 纪律 /
执行协议（栞问 → 栞启动 → 用户盯屏裁决）/ 合成调试环（**保留，降级为调试工具**，
`DHVK_NOWALL/NOPROBE` 因子开关已就位，承载全带机制验收）。

**非目标（S3/S4）**：距离 alpha ramp、`ModelOffset`（≥32km）、地曲率项、
10/20km 截图打磨（S3）；GPU mesher（S4）。

**诚实边界（写入验收口径）**：步骤 1~4 在 S1 距离带 [448, 2048]（RD32）内验证机制；
官方雾 end = 512@RD32 → 此阶段 >512 的带内几何被雾吞（S1 墙 B 实证），
**视觉验收以近带薄切片 + 合成调试环 + 价目表遥测为主**；全带阶梯视觉在步骤5
（fog end + 远平面扩展后）兑现为总闸门。

## 1. 验收闸门（逐步，VVL 标准档 + 全开档双零是每步硬门）

| 步骤 | 闸门项 | 门级 |
|---|---|---|
| 1 秒表 | far pass 每帧 GPU 代价读出进日志（数值量级合理：5090、RD32 带内 O(0.x ms)）；预算门日志（far pass > 1ms → 明确 `farpass budget breach` 行，只报不行为改）；`DHVK_NOTICK=1` 时 pass 录制与 S1 字节流一致；VVL 双零；与基线 diff 干净（仅多 query pool 对象） | 硬 |
| 2 阶梯 | S1 带内 L0/L1 阶梯跑通（带宽表 §3.1 初值）：价目表出现 L0/L1 分行、数值合理；L0/L1 交界无 pop-in（淡入沿用 per-cell visibility，用户目验近带切片 + 合成环）；VVL 双零；+1ms 预算（RD32/5090）；per-cell UBO 数据源 = 堆（探针判别式，§3.3）；L1+ 几何被近景深度遮挡（定点抽查） | 硬 |
| 3 剔除 | 秒表 A/B 实证节省（far pass µs 或 drawn cell 数，山地场景前后对比）；山地剪影无破洞（用户目验 + 确定性山地合成场景）；帧级剔除 CPU 成本 ≤ 0.2ms（计时）；VVL 双零 | 硬 |
| 4 VRS | rate image 取代固定 2×2 且 VVL 双零；秒表 delta 三组数（rate map vs 固定 2×2 vs 全 1×1）落日志；用户 A/B 目验（4×4/8×8 粗糙度终裁）；8×8 若 5090 不暴露则封顶 4×4（运行时预算，非设备兜底） | 硬 |
| 5 远平面三件套 = **10km 总闸门** | 10km 带（L0~L3 全阶梯）填满地平线（用户目验）；5090 +2ms/帧以内（秒表）；VVL 双零；价目表 L0~L3 分行合理；与 vanilla 基线 diff 干净（帧提交/队列不变）；深度精度抽查（10km cell 边缘无抖动/无 z-fight，探针墙 + 目验）；ndcz 重推导写入文档 | 硬 |

**负门槛**（S1 先例延续）：硬门槛自禁用时，秒表/阶梯/剔除/VRS 全部随之不启用
（无独立启用路径），游戏照常干净运行。

## 2. 步骤 1 — GPU 秒表（详设）

### 2.1 勘察裁决（任务 0，2026-09-15 实测，全文见 s2 笔记 §1）

1. **官方帧同步面 = timeline semaphore**（`VulkanCommandEncoder` 已核实）：
   `submitSemaphore`（timeline，L68-77 创建）+ 每帧 submit 尾部
   `signalSemaphore(submitSemaphore, currentSubmitIndex, 0x10000)`（L198）；
   fenceless `vkQueueSubmit2KHR(queue, submits, 0L)`（`VulkanQueue.java:124`）；
   官方 `awaitSubmitCompletion`（L584-611）用 `vkWaitSemaphores` 轮询 timeline
   值推进 `completedSubmitIndex`——**仅当官方代码 await fence 时才推进**，
   故我方读回安全性不依赖它，直接用 GPU 侧实时值：
   `VK12.vkGetSemaphoreCounterValue(device, submitSemaphore) ≥ 本帧写入时的
   currentSubmitIndex` ⟹ 该帧 CBU（含我方 pass）GPU 已全完 → 可非阻塞读回。
2. **官方时间戳写路径已存在且公开**：`VulkanCommandEncoder.writeTimestamp(
   GpuQueryPool, int)`（L636-640）= `vkResetQueryPool` +
   `KHRSynchronization2.vkCmdWriteTimestamp2KHR(cbu, 0x10000, pool, i)`
   （官方 `getTimestampNow` 同款调用，L648，VVL 实证过）；
   `VulkanDevice.createTimestampQueryPool(int)` 公开（L323）→
   `VulkanQueryPool`（TIMESTAMP_EXT 64-bit，implements Destroyable → dispose 纪律覆盖）。
3. **LWJGL 类面随包核实**：VK12（`vkGetSemaphoreCounterValue`/`vkGetQueryPoolResults`）、
   KHRSynchronization2（官方代码已在用）、KHRShaderClock 族（备用，步骤1 不用）。
4. **三层退路 → 取第①层**：① 官方 timeline 搭车 + 官方 writeTimestamp（勘察证明
   面全在，**用户提案裁定采纳**）→ ② 若探针 shadow 不顺，读回层换
   `completedSubmitIndex` 影子（写时戳层不变）→ ③ KHRShaderClock（shader 级，
   留作未来"per-LOD shader 代价拆解"升级路径，S2 不用）。零新增机制：
   唯一新设备对象 = 一个 query pool（官方工厂创建）。

### 2.2 机制

- **查询池**：mod init（硬门槛通过后）`((VulkanDevice) device).createTimestampQueryPool(N)`；
  N 一步到位 = 2 + 2×4 = 10（pass 总夹一对 + 4 个 LOD 档各一对，步骤2 起启用——
  R-c 同款"表不重建"：池只建一次）；
- **发射点**：S1 既有发射点（setPipeline 后 / draw 前，encoder 当前 CBU，
  `DhvkCommandEncoder.dhvkCurrentCbu()` 同一时机）加一对/多对
  `dhvkWriteTimestamp(pool, slot)`（接口扩展，探针 mixin 委托官方公开方法）；
  步骤2 起按 LOD 档分组 draw（按 level 排序分组），每组前后夹一对（槽位预摊）；
- **每帧读回**（CPU 侧，帧回调内、pass 录制前，零 CBU 开销）：
  `vkGetSemaphoreCounterValue` 判 ready → `vkGetQueryPoolResults(
  device, pool, first, count, ptr, 0, VK_QUERY_RESULT_64_BIT)` 非阻塞 →
  各段时长 = (end−start) × 2^(−timestampValidBits) 秒（device property，
  init 时 dump 进日志）→ µs；
- **输出与预算门**：日志节流（每 120 帧 + 档位/带宽变化即打一行）：
  `[dhvk] farpass gpu: pass=xxx.x µs | L0=.. L1=.. (cells=.. band=[..])`；
  预算：步骤1~4 门 = far pass ≤ 1ms，步骤5（10km）门 = ≤ 2ms；
  超限 → 明确 breach 行（只报不改行为——自动调节控制器见 §3.2）；
  因子开关 `DHVK_NOTICK=1` = 不写 timestamp、不读回（pass 录制回到 S1 字节流）；
- **关断纪律**：query pool（device 子对象）入 `FarTerrainRenderer.dispose()`
  幂等释放序列（vkDestroyDevice 之前）；
- **VVL 预期**：TIMESTAMP_EXT 池 + writeTimestamp2KHR + counter 读 = 官方代码
  自身行为（getTimestampNow 同源），双零为期望值；若被点名按报错回填（笔记 §5 R2）。

## 3. 步骤 2 — LOD 阶梯

### 3.1 级别与带宽表

- 级别：**L0=32**（S1 cell）/ **L1=128** / **L2=512** / **L3=2048**
  （×4/级，3 个父级；同一 shader，LOD 仅 cell 边长——总计划裁决，无变体）；
- 带宽表（初值；剔除算术 + 用户目验定稿；切换点设 ±20% 滞后带防抖）：
  - S1 带（步骤 1~4，RD32 [448, 2048]）：L0 [448, 1024) / L1 [1024, 2048]；
  - 10km 带（步骤5，远平面扩展后）：L0 [fogStart, 2048) / L1 [2048, 8192) /
    L2 [8192, 10240) / L3 [10240, bandFar)；bandFar ≈ 10240·1.2 ≈ 12288（初值）；
- 剔除算术（笔记落数）：10km 处视锥跨度 ≈ 1.4d ≈ 14300 blocks → L3 ≈ 7 列 ×
  数行 ≈ 数十 cell；L2 @8-10km ≈ 百级；L0/L1 可见集 ≤ 2000（S1 口径）——
  驻留上限 8192 与 R-c 预摊堆表（不搬家）均有充足 headroom；
- 几何尺寸：Lk 网格恒 33×33 顶点 / 2·32·32 三角（仅间距 ×4^k 变）→
  每 cell 几何子区预算与 S1 相同（R-c 不变）。

### 3.2 上卷与数据地基

- **父 cell（level k）= 其 16 个子（level k−1）cell 列顶面的 4×4 子采样**：
  L1 顶点间距 4 blocks（128 块域）… L3 间距 64 blocks（2048 块域）；
- **可建性（严格版，R3 备有放宽）**：父 cell 仅在 16 个子 cell **全部**有
  CPU 高度场数据时可建（all-or-nothing；山地稀疏带父建滞后 = R3，放宽到
  ≥90% 子可用，缺口用邻列顶/最后一次数据补，目验裁决）；
- **CPU 数据与 GPU 驻留解耦**：注册表分两层——GPU 驻留（堆子区，LRU 驱逐）与
  CPU 高度场（**滞后带内保留 = R5 的"最后一次数据"**）；GPU 驱逐不丢 CPU 数据；
  CPU 数据驱逐 = 超出最远子带驱逐滞后带 且 无父 cell 引用（引用计数）；
- **tile 裁定（R-b 的 S2 细化，红线加强非放松）**：**block tile 仅 L0（32³ 量化
  索引，S1 起已同步上传）**；L1~L3 = 对 4×4 / 16×16 / 64×64 个 L0 tile 组的
  **引用**——S4 GPU mesher 的输入恒为 L0 tile（设备侧由 L0 数据生成任意级几何），
  父级不需要自己的 tile，R-c 子区预算无需重摊（R4 销账）；
- **per-LOD 流式预算**（初值，步骤2 起被秒表驱动自动调节）：
  帧建 L0 ≤8 / L1 ≤4 / L2 ≤2 / L3 ≤2（S1 口径延续）；帧驱逐 L0 ≤2 / L1 ≤2 /
  L2 ≤1 / L3 ≤1；驱逐滞后 2 帧 + 带宽切换 ±20% 防抖；
- **自动调节（"给省货定价"的闭环，总计划 S2 原文落地）**：秒表（步骤1）输出
  per-LOD GPU 代价 → 每帧简单积分环：far pass 总代价 > 预算（初值 1.5ms，
  步骤5 为 2ms）时，自最远档起逐级把该档帧建预算 ×0.8（有 floor）；
  有富余时 ×1.1 回补（有 cap）；步骤5 起 bandFar 亦可收缩（×0.9，远平面
  随 bandFar 重建投影）——控制器 = 每帧一步积分，无 PID、无预测。

### 3.3 渲染集成（含 per-cell UBO 并入堆）

- **draw 列表**：per-level 帧剔除（S1 姿势：距离带 + cell AABB×Y 带 + joml
  `FrustumIntersection`）→ 按 level 分组排序 → 每组内 per-cell draw
  （heap 每帧一次整表 bind 不变；per-cell 偏移 = S1 的 push constant 通道不变）；
- **per-cell UBO 并入堆（S2 项5，官方通道退役）**：`ChunkSectionInfo`
  （std140：ModelViewMat/ChunkVisibility/TextureSize/ChunkPosition）成为堆内
  描述符（cell 子区内独立槽，与 VBO/IBO 同款 `HEAP_WITH_CONSTANT_OFFSET`
  mapping source）；per-frame visibility 淡入 = 堆窗口直写 4B（S1 路径 A 就位）；
  **官方 `writeChunkSections` 动态通道退役为 push 壳**：官方
  `pushDescriptors` 机制要求 UBO 描述符存在且 buffer 有效（S1 笔记 §8.2
  双通道实证），故官方 slice 保留为壳（per-frame 写与堆相同的 visibility 值
  作壳数据），**shader 数据权威 = 堆**；
  **数据源判别探针**（S1 判别式同款）：堆内写一个仅堆可见的标记值（如
  TextureSize 壳=(1,1) / 堆=(7,7)）→ 屏幕/探针读出哪边 = 数据走哪边，
  步骤2 验收的实证手段；
- **BGL 集合**：GLOBALS + MATRICES_PROJECTION + FOG + CHUNK_SECTION（官方现成，
  S1 已用）+ VBO/IBO 幻象绑定（S1）——CHUNK_SECTION 的 mapping 重定向到堆后，
  集合不变，只换数据源。

### 3.4 步骤 2 闸门 = §1 步骤2 行（含"U_B 数据源 = 堆"探针行）。

## 4. 步骤 3 — 遮挡剔除（两层，两层都 CPU 侧、都便宜）

### 4.1 (a) 构建期点剔除（总计划原文，DH `FullDataOcclusionCuller` 姿势）

- 规则（读 DH 源定稿，refs/dh 摘录见总计划 §4b；初形）：列顶 p 被剔 ⟺
  ±X/±Z 四个方向上，**同 cell + 邻 cell 边缘带（1 block 外沿，S1 33×33 网格
  边界列核实是否已含 1 列 margin，不含则提取时补 1 列）**存在比 p 高的列
  （任意低位视线的遮挡代理）；
- 输出 = 顶点保留、索引重建（子区复用，R-c 预算覆盖）；运行时零成本；
- 山地场景 = 该层的正主（三角面大降 → 步骤4 的 rate map 有价可降）。

### 4.2 (b) 帧级区域剔除（用户步骤3 提案）

- 每帧对每个剔除后候选 cell：相机 → cell 中心 + 4 角共 5 点，任一点可见 =
  可见（保守，防破洞）；视线判定 = XZ 平面沿线步进查 **L0 CPU 高度场列顶**
  （步长 16~64 blocks，≤20 样本，查表 O(1)）；
- **缓存**：per (cell, 数据 generation, 相机 256-block 格位) 戳；
  相机位移 ≥16 blocks 或 cell generation 变 → 重算；
- 被挡 cell = 不进本帧 draw 列表，**驻留保留、LRU 不刷新**（被挡时可自然驱逐，
  省驻留）；下一帧视线恢复 → 立即回归（零 pop-in）；
- 成本目标 ≤0.2ms CPU（2000 cell × 20 样本 × 查表）；超预算时退路 = 样本数
  减半 / 仅对 L1+ 启用（L0 近景剔除收益小，R6）。

### 4.3 证据：秒表 A/B（山地合成场景：确定性山地高度场加入合成调试环，
开关 `DHVK_MOUNTAIN`）——far pass µs 与 drawn cell 数前后对比落日志。

## 5. 步骤 4 — VRS rate map（光栅化按距离定价）

### 5.1 机制

- **rate image 取代 S1 固定 2×2**（dynamic rendering 形态，类面已核实随包）：
  pass 实例侧 `VkRenderingFragmentShadingRateAttachmentInfoKHR`
  （sType 1000378000）pNext 挂 `VkRenderingInfo`；pipeline 侧 S1 的
  `VkPipelineFragmentShadingRateStateCreateInfoKHR` 状态结构参数调整
  （rate image 与固定率的组合语义 = 读码先决 + VVL 终裁，笔记 §4）；
- **rate map 生成（每帧，CPU，4KB）**：64×64 R8 持久 image
  （format/layout/usage 按规范文本 + VVL 终裁，初值 R8 + GENERAL +
  TRANSFER_DST；host-visible buffer + `vkCmdCopyBufferToImage` 上传，无 layout
  切换）；每 texel = 一条屏幕射线（相机基 + fov，per-frame 参数已在手）→
  该射线进入 L_i 距离带的距离 → rate code：**L0/L1 → 1×1，L2 → 2×2，
  L3 → 4×4（5090 暴露 8×8 则 8×8**，init 时 dump
  `VkPhysicalDeviceFragmentShadingRatePropertiesKHR` + NV enum properties
  裁决——运行时预算，非设备兜底）；
- rate = **屏幕位置**的函数（非 per-cell 档）——"按距离定价"最字面形态；
  64×64 的 map 边界天然平滑，无档边界硬跳；
- **生效证据（总计划"S2 内定一种"就此定案 = 双证）**：秒表 delta 三组数
  （rate map vs S1 固定 2×2 vs 全 1×1，far pass µs）为主证 + 用户 A/B
  目验（4×4/8×8 粗糙度终裁、截图存档）为视觉终裁。

### 5.2 手术面

- S1 的 pNext @Redirect（`VkGraphicsPipelineCreateInfo.pNext` 调用点）不变，
  只换结构体参数；**pass 实例侧 pNext 注入点 = 读码先决**：官方
  createRenderPass → `vkCmdBeginRendering` 的 `VkRenderingInfo` 构建处；
  无注入点 → 退路 = 自建 raw `vkCmdBeginRendering` 复刻官方 renderingInfo
  （S1 pipeline 自建先例同款，render pass 对象兼容性 VVL 核验，R7）。

## 6. 步骤 5 — 远平面三件套（S2/S3 边界 A 的落地）

### 6.1 远平面 hook（2026-09-15 勘察半成品，落点已收窄）

- 官方路径实测：`GameRenderer.renderLevel` L532
  `new Matrix4f(cameraState.projectionMatrix)`（投影矩阵在 renderLevel 前
  已建入 `CameraRenderState`，含 bob/效果旋转变换 L539/L544-552）→ L554
  `RenderSystem.setProjectionMatrix(levelProjectionMatrixBuffer.getBuffer(
  projectionMatrix), PERSPECTIVE)`（static，RenderSystem.java L177）；
- hook 两候选（读码定夺）：**①** `CameraRenderState.projectionMatrix` 的
  构建点（Camera 侧 far 输入，改 far 后矩阵重建——最干净，官方 bob/效果
  变换自动保留）；**②** static `setProjectionMatrix` @Inject 兜底：从 slice
  矩阵解析 near/fov → 以新 far 重算 3 个元素（m[10]/m[2][3]/m[3][3]）写回
  slice（每帧 16-float 重写，便宜）；
- far' = max(官方 far, bandFar·k)，k 的系数由视锥角距实测定（视锥角半径 =
  bandFar，角距 = bandFar/tan(hfov/2)·… 读码 + 首跑标定）；near 不动（0.05，
  reverse-Z 近端精度与 far 无关）；
- 官方 ndcz 由投影矩阵值在 shader 内计算 → far 扩展 GPU 侧自动正确，
  我方只做解析式重推导 + 精度抽查。

### 6.2 fog end 扩展

- 写路径实测：`GameRenderer.renderLevel` L556
  `fogRenderer.updateBuffer(cameraState.fogData)` → L557
  `getBuffer(FogMode.WORLD)` → `RenderSystem.java:281`
  `renderPass.setUniform("Fog", fog)`（per-pass push，BGL
  `BindGroupLayouts.FOG`）；
- hook = `FogRenderer.updateBuffer` 单点（读码确认）：官方写入后覆写
  std140 Fog 块的 3 个字段 `FogRenderDistanceEnd`/'FogSkyEnd`/
  `FogCloudsEnd` = bandFar（块偏移按 fog.glsl 声明：FogColor@0..15 /
  EnvironmentalStart@16 / EnvironmentalEnd@20 / RenderDistanceStart@24 /
  RenderDistanceEnd@28 / SkyEnd@32 / CloudsEnd@36——S0 已核实的资产，
  偏移不变性读码复核）；FogColor 与其余字段不动；
- 效果：剪影 = "地形基色 → 雾色" 随距离渐变消融 → **S1 顺延的"剪影溶进雾"
  视觉验收在此兑现**（10km 带全可见）。

### 6.3 ndcz 重推导

- 按 `docs/mc26.2-vk-reference.md` §2 方法，对 far'=f 重推 reverse-Z 解析式
  （文档中 `ndcz(d)=19.997/d+0.0031` 为 f=320 特例 → 参数化公式写入文档 +
  本笔记），并给出 far' 下的近/远端深度分辨率数值表；
- 精度抽查（验收）：10km cell 边缘稳定（无抖动/无 z-fight）= 探针墙定点 +
  用户目验；官方近景深度行为与基线 diff 干净。

### 6.4 步骤 5 = 10km 总闸门（§1 步骤5 行，S2 收尾）。

## 7. 风险与退路

| # | 步骤 | 风险 | 退路 |
|---|---|---|---|
| R1 | 1 | timeline `vkGetSemaphoreCounterValue` 值更新滞后（驱动实现差异）→ 读回永不 ready | 读回判据换 `completedSubmitIndex` 影子（官方 await 路径触发）；再退 = 自建小 binary fence 搭 fenceless submit（S1 已验证 submit2 面）——读回层与写时戳层解耦，互不影响 |
| R2 | 1 | writeTimestamp 的 stage 0x10000 被 VVL 点名（我方 CBU = 官方 encoder 当前 CBU，与官方 getTimestampNow 同源，预期不中） | 按 VVL 报错回填 stage mask（笔记 §1 留痕） |
| R3 | 2 | 父 cell 16 子严格 all-or-nothing → 山地稀疏带父建滞后、阶梯缺口 | 放宽 ≥90% 子可用（缺口 = 邻列顶/最后数据），目验裁决；仍丑则父档带宽表内收（band 切换点提前） |
| R4 | 2 | R-c 子区预算 vs L1+ 需求 | 已销账：Lk 几何与 L0 同拓扑（33×33/2·32·32），tile 仅 L0（§3.2 裁定）——预算不变 |
| R5 | 3 | 视线判定保守偏严 → 误剔（"谷"里该见的被挡） | 5 点可见即可见 + 滞后立即恢复（下帧回归零 pop-in）+ 山地合成场景 A/B 目验 |
| R6 | 3 | 帧级剔除 CPU 超 0.2ms | 样本减半 / 仅 L1+ 启用（L0 恒画） |
| R7 | 4 | 官方 createRenderPass 路径无 VkRenderingInfo pNext 注入点 | 自建 raw vkCmdBeginRendering 复刻官方 renderingInfo（S1 pipeline 自建先例；VVL 核验兼容） |
| R8 | 4 | rate image format/layout 被 VVL 判不合规 | 按报错回填（规范文本 + VVL 终裁；笔记 §4 留痕） |
| R9 | 4 | 5090 不暴露 8×8 | 封顶 4×4（运行时预算，非兜底；init dump 留证） |
| R10 | 5 | cameraState 构建点分散/不稳 | 候选② static setProjectionMatrix slice 重写（§6.1） |
| R11 | 5 | Fog 块字段偏移与 fog.glsl 声明不符 | spvc 反射 shader 的 Fog 块布局交叉验证（S0 资产，偏移不变性预期成立） |

## 8. S4 后路合规（红线在 S2 的落点）

| 红线 | S2 落点 |
|---|---|
| R-a 注册表/堆表是几何唯一权威，渲染器只消费"cell → (几何子区, UBO)" | 阶梯/剔除/秒表/rate map 全是表的**消费者/观察者**；"cell → (子区, UBO)"契约一字不改（UBO 并入堆 = 数据源搬进权威表内，契约不变） |
| R-b 32³ block tile 从 S1 起同步上传 | tile 仅 L0（S2 细化裁定 §3.2，红线加强非放松）：GPU mesher 输入恒为 L0 tile，父 LOD = L0 tile 组引用 |
| R-c 堆子区按 S4 最坏预算 init 预摊，不重建不搬家 | Lk 几何与 L0 同拓扑 → 子区预算不变；query pool 一步到位 N=10；堆表零搬家 |
| R-d mesher 接口 = DAG（ready→提取→上传→表更新），执行器可换 | 阶梯 = "提取/表更新"阶段的细化（父 cell 表更新）；点剔除 = 提取阶段内规则；区域剔除 = 表更新前的 draw 列表；秒表/rate map = 纯观察/渲染侧，不进 DAG |
| R-e 顶点格式（position+基色，无光）不变 | S2 零格式改动；rate map 是屏幕空间定价，不进顶点/格式 |
| R-f 不得 bake 单层天空线假设 | 阶梯 = 多层点列（R-f 超集，总计划原文）；10km 带 = 四层天空线并存，S4 任意三角汤走同一张表 |

**观察**：S2 未引入任何新 ABI 假设——S4 换执行器（GPU mesher）时，
阶梯/剔除/秒表/rate map 全部原样工作（均为 CPU 侧消费者或渲染侧定价）。

## 9. 执行协议（与 S1 一致，按步切片）

1. 栞问 → 栞启动：S1 同款命令块（`:mod:runClient --args="--graphicsBackend
   vulkan --vulkanValidation"`，后台 job + tee，日志标签 `s2-run-<步骤>-<HHMMSS>`）；
2. 每步：（有先决则先勘察 → s2 笔记回填）→ 代码 → `:mod:build` + lint →
   VVL 标准档 + 全开档两轮 → 用户目验裁决 → 证据归档 `docs/s2-acceptance/`
   + commit（含 ≤5 行中招清单）；
3. 每步验收结果回填总计划 §6 与文档（ndcz 公式 → mc26.2-vk-reference.md）；
4. 代码读码笔记契约同 S1：`docs/s2-code-reading-notes.md` 是后续任务事实
   来源，与代码冲突时以笔记为准。

## 10. 开放读码先决清单（按步销账）

- **步骤1（本轮任务0 已销）**：帧同步面 / writeTimestamp / createTimestampQueryPool /
  LWJGL 类面 / timeline 读回 → 裁决 = 第①层（§2.1，笔记 §1~§2）；
- **步骤2**：剔除算术落数（各级可见 cell 数 / 驻留与堆容量确认）；上卷网格与
  33×33 margin 核实；visibility 双通道写路径（官方 slice 壳 + 堆窗口）；
  5090 `maxResourceHeapSize` vs 全表（L0~L3）尺寸；
- **步骤3**：DH `FullDataOcclusionCuller` 具体规则读源（refs/dh）；L0 高度场
  CPU 侧快速查询接口设计；山地合成场景（确定性、可开关）；
- **步骤4**：官方 createRenderPass → VkRenderingInfo 构建点（有无 pNext 注入）；
  rate image 规范文本（format/layout/usage/组合语义）；5090 FSR properties
  dump（supported rate 集，8×8？）；rate image × dynamic rendering × heap
  绑定共存的 VVL 首验；
- **步骤5**：`CameraRenderState.projectionMatrix` 构建点（Camera 侧）；
  `FogRenderer.updateBuffer` 单点确认 + Fog 块偏移复核；ndcz 参数化公式；
  bandFar·k 系数首跑标定。
