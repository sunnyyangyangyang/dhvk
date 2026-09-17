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

## §10 S2v2 任务0' 读码销账（2026-09-16，pivot 侦察）

### §10.1 tile margin 语义（计划 Task 0 Step 2）

**代码事实**：现行 S1 远 pass 的几何 100% 来自合成调试环——`FarTerrainRenderer.java:398`
`renderPass.drawIndexed(VoxelWallSynthesizer.TOTAL_INDICES, ...)`、L434-436 每帧
`VoxelWallSynthesizer.synthesize(...)`（32×32 quad 行波墙，33×33 = 顶点晶格 GRID+1，
**与 tile margin 无关**）。`FarTerrainRenderer.java` 全文 height/column/grid 命中 = 0：
S1 代码里不存在 per-chunk tile 概念，margin 是 pivot 新设计问题，非既有代码问题。
**裁决（初值）**：tile = 32³ 本体；面剔除越界时**按需取邻 tile 邻边**（`BlockTileView`
查邻 tile 同侧 1 层），邻 tile 缺失（从未产生）该侧按空气；**不存 33³**（内存友好，
与规格 §4.1 / 红线 R-b 一致）。后续若实测“取数开销 > 33³ 存储成本”再改，参数化留口。

### §10.2 剔除算术 + 32MiB 驻留约束（计划 Task 0 Step 3，含 §10.3 发现）

视锥角半径 = bandFar 的 10km 带表（Task 7 启用；fogStart = 精度带边缘，首跑标定）：

| 级别 | 带宽 | cell 边长 | 可见 cell 数（环形带面积 / cell²，纸面估值） | 每 cell 几何量级（贪心合并后，实测前估值） |
| --- | --- | --- | --- | --- |
| L0 | [fogStart≈1024, 2048) | 32 | ≈π(2048²−1024²)/32² ≈ 9.8k | 混合格最差估 0.5~1MB |
| L1 | [2048, 8192) | 128 | ≈π(8192²−2048²)/128² ≈ 3.8k | ≈0.2~0.5MB |
| L2 | [8192, 10240) | 512 | ≈450 | ≈50KB |
| L3 | [10240, bandFar≈12288) | 2048 | ≈35 | ≈30KB |

**关键发现（§10.3 联动）**：maxResourceHeapSize = 32MiB（设备硬上限，见 §10.3）。
pivot 的几何字节流在堆子区（R-a/R-c）→ **几何驻留量 = 硬约束**：
32MiB 总池 − 描述符表（约 25k cell × 3 槽 × 槽宽 ≈ 3~5MB）− 驱动预留（S1 实测 96KB）
≈ **27~28MiB 几何池**。上表估值满配 ≈ 11.6MB（中位）～ 25~30MB（最坏）——**贴着上限**。
**后果（设计输入，Task 1 / Task 7 消费）**：
1. Task 1 的 R-c 常数（spike 实测最坏 × 1.5）必须与“驻留 cell 数 × B ≤ 28MiB”联立求解：
   B 太大 → 驻留数缩到近带 → 10km 阶梯的 L0 带宽要上收（fogStart / L0 带边缘 = 标定参数）；
2. 几何池溢出**不算 fallback**：LRU 驱逐几何 = 既有流式机制，重入带内 = 重上传（不重提取，
   规格 §4.2 触发规则）；“heap budget breach” 日志 = 报警（R-c“只报警”语义不变）；
3. 带宽表（Task 7）= **联合标定对象**：bandFar / fogStart / L0 带边缘三参数一起对
   “32MiB 驻留预算 + 地平线填满 + 深度精度”三条件标定，不是单参数各自为政。

### §10.3 maxResourceHeapSize 数值（计划 Task 0 Step 4）

**实测（本机 RTX 5090，系统 vulkaninfo，驱动 Vulkan 1.4.341 层）——2026-09-16**：

| 属性 | 值 |
| --- | --- |
| **maxResourceHeapSize** | **0x02000000 = 32 MiB（硬上限，§10.2 驻留约束的来源）** |
| resourceHeapAlignment | 0x40 = 64（S1 代码按 4096 对齐 = 64 的倍数，兼容） |
| minResourceHeapReservedRange | 0x200 = 512（S1 run20 实测驱动预留 96768B > 此最小值，以实测为准） |
| descriptorHeap 特性 | true（feature 已开，S1 已跑通） |
| maxSamplerHeapSize | 0x20000 = 128KB（sampler 堆，独立于 resource 堆；本计划暂不用） |
| tensor 堆属性 | 全 0（本设备 tensor 堆未启用，与 pivot 无关） |

代码侧：`DescriptorHeap.java:107` 已读 `hp.maxResourceHeapSize()`，但仅在预算降级时落日志
（S2 步骤1 日志无降级 → 数值未入档）。**本任务唯一新代码 = init 处加一行无条件 info 日志**
（已加，compileJava 验过），此后每次跑机数值入日志留档。S1 堆表（描述符表）在 32MiB 内裕量
大（槽位制，cell 容量 25k+）；pivot 几何入堆后 32MiB 变为**紧约束**（§10.2）。

### §10.4 visibility 双通道现状核对（计划 Task 0 Step 5）

**代码事实（现行 HEAD）**：
1. 堆侧通道（S1 资产，在）：`FarTerrainRenderer` L101-109 phantom UBO 机制
   （“双通道:classic push 描述符 + 管线创建期静态堆 mapping，故 render() 里必须 setUniform
   填充”）；L223-231 minUniformBufferOffsetAlignment 对齐审计 + run29 哨兵缓冲；
   L351 走官方 `RenderSystem.getDynamicUniforms().writeTransform(modelView)` 壳。
2. 官方壳通道（在）：`DynamicUniforms.java`（mc-src/client/net/minecraft/client/renderer/）
   L19 `CHUNK_SECTION_UBO_SIZE`（mat4+float+ivec2+ivec3 std140）+ `writeChunkSections`
   族方法（S1 笔记 §8.2 行号 61/65 对应，26.2 树同形）。
**结论**：双通道都在；Task 2 Step 6 的“CHUNK_SECTION 成堆内描述符 + 壳退役为 push 壳 +
4B visibility 直写堆窗口 + (7,7)/(1,1) 判别探针”按 S2 v1 §3.3 原样实施；写序（堆先壳后）
实施时以首跑 VVL + 判别探针结果为准回填本节。

### §10.5 官方 terrain atlas 绑定路径（计划 Task 0 Step 6，关键项）

**代码事实**（mc-src/client/com/mojang/blaze3d/vulkan/，注意 26.2 反混淆树无 src/main/java 层）：
1. atlas 载体：`LevelRenderer.java:516`
   `GpuTextureView blockAtlas = this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();`
   → 官方 block atlas = 单一 GpuTexture（Vulkan 后端 = VulkanGpuTexture），句柄链：
   `client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)`（GpuTexture）
   → `getTextureView()`（GpuTextureView = VulkanGpuTextureView）。
2. 官方采样句柄面（全部 public，只读可取）：`VulkanGpuTextureView.vkImageView()`（L63）；
   `VulkanGpuTexture.vkImage()`（L114）；`VulkanGpuSampler`（设备 sampler 对象，
   getMinFilter/getAddressModeU 等访问器，L71-86）。
3. 官方 terrain 管线（`RenderPipelines.java`）：`SOLID_TERRAIN` = 命名注册
   （“pipeline/solid_terrain”）；TERRAIN_SNIPPET = GENERIC_BLOCKS_SNIPPET（FOG +
   SAMPLER0_SAMPLER2 共享 sampler BGL + 顶点格式 DefaultVertexFormat.BLOCK）+ PROJECTION
   + CHUNK_SECTION + core/terrain 着色器。`core/terrain.fsh:7` = `uniform sampler2D Sampler0;`
   → atlas 采样走共享 SAMPLER0 槽，**每 pass 内容可变**（帧内其他 pass 会换 setSampler0 内容）。
**裁决（初值）**：我方远 pass **自建 sampler 组**（规格 §3 台账退路，零官方触碰点）：
自建 VkSampler（我方设备对象；参数对齐官方 block atlas 语义 = NEAREST + mips，实施时读
官方 sampler 访问器取值或固定值）+ 官方 `vkImageView()` 句柄（只读 long）→ 写我方自建
BGL 的 descriptor set。不共享官方 SAMPLER0 槽（帧内可变 = 隐患）。probe 兜底 = S1 探针
先例（自建 sampler 组也走 probe 管线验证）。`TextureAtlasSprite` 枚举全集 API（SpriteRectTable
任务3 用）实施时补钉（TextureAtlas.getSprite(Identifier) 链）。

### §10.6 26.2 精灵/染色 API（计划 Task 0 Step 7）

**代码事实**：
1. state → 精灵：`Material`（record，net/minecraft/client/resources/model/sprite/Material.java:9
   = (Identifier sprite, forceTranslucent)）+ `Material.Baked(TextureAtlasSprite sprite, ...)`（L29）
   = 官方网格链（BlockModelSet/ModelBlockRenderer → 面 → Material.Baked）里承载精灵的位置。
   `TextureAtlasSprite`（net/minecraft/client/renderer/texture/TextureAtlasSprite.java）：
   `getX()`(L40)/`getY()`(L44)/`contents()`(L56, SpriteContents 含尺寸) = UV 矩形来源；
   atlas 像素坐标 → UV = 坐标 / atlas 尺寸（LevelRenderer:517-518 官方同款换算）。
2. 染色：`net/minecraft/client/renderer/BiomeColors.java` 静态 API（CPU 侧可直调）：
   `getAverageGrassColor(BlockAndTintGetter level, BlockPos pos)` / `getAverageFoliageColor` /
   `getAverageDryFoliageColor` / `getAverageWaterColor` → int rgb（L18-30）；resolver 常量
   L9-12（GRASS/FOLIAGE/DRY_FOLIAGE/WATER，`Biome::getGrassColor` 等）。
3. 我方消费方式（Task 1/3）：mesher 输入 = 预计算 state → (面精灵代表 + 染色类) 表；
   逐顶点 tint = L0 级按位置调 BiomeColors（level 即 Level 对象，S1 渲染回调内可取）；
   L1+ 级 tint = 子级多数投票（规格 §4.2）。spriteID 全局表（SpriteRectTable）= atlas 精灵枚举
   + 索引分配（任务3）；u16 容量 = 精灵总数（N5 风险项，任务3 Step 1 断言）。

### §10.7 cgerikj 参照仓库（计划 Task 0 Step 8，待用户点头后 clone）

已 clone（用户点头，2026-09-16）：`refs/binary-greedy-meshing`（浅克隆，gitignore 内，不进构建）。
**算法核心（README 实证，C++ 参照）**——三步，与规格 §6 设计一一对应：
1. **occupancy mask**：64×64 数组 × 64bit 字（0=air / 1=opaque，在 mesher 之外生成、可缓存更新）
   → 我方 = tile 32³（30 字/行 × 32 行），mask 语义扩为“非 air 且可见”（含 cutout，规格 §6）；
2. **face mask / 隐藏面剔除**：每方向 62×62 数组 × 64bit 位掩码（位面邻 air 才可见，6 方向）
   → 我方 32³ 同构缩小为 30×30；六方向全出面（侧/底面不省，R-f 红线：崖壁/悬挑/洞口可读）；
3. **贪心合并**：逐面迭代位运算 64 面一批合并；**“lookup original voxel types to check whether
   two voxel faces can be merged”** = 合并判定查体素类型 → 我方 MC 化 = 类型即 (spriteID, tint)
   二元组（仅同 sprite + 同 tint 才合并，规格 §6 改造点 2）。
**输出**：v2 原形 = 8B/quad 打包（6bit×5：x/y/z/宽/高）+ per-quad 类型，vertex pulling 渲染；
README 明示“可改成 4/6 个常规顶点/quad”→ 我方 = SoA v2 显式顶点（规格 §5.1，27B/顶点，
8B 打包解包 = uvAbs 区间：面轴坐标 + 面轴宽度 → 每顶点 uvAbs，轴向由面方向定，任务3）。
**性能锚点（README）**：64³ chunk 单线程 50~200µs（均值 74µs，Ryzen 3800x）→ 我方 L0 tile
（32³ = 1/8 体素数）预估 10~30µs 量级，任务1 spike 实测销账（规格 §8 闸门 1 通过标准之一）。
**AO 参照**：v2 无 AO，**v1 分支有 baked AO**（README 明示）——任务4 的 AO 邻角规则若需参照
细节，切 v1 分支读（不额外 clone，同仓库 `git fetch origin v1.0.0` 即可）。
