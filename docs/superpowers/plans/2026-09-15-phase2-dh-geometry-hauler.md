# 阶段二 MVP 计划：DH 式超远方几何搬运工（geometry-only hauler）

- 日期：2026-09-15
- 规格依据：README《阶段二基线裁决(2026-09-14 定)》+ 阶段一 spec
  (`docs/superpowers/specs/2026-09-13-dh-vk2026-env-design.md` §7 原则)
- 参照系：`refs/caustica/`（hook/句柄模式）、Distant Horizons（已 clone 于
  `refs/dh/`：GitLab distant-horizons-team/distant-horizons @ f5d2f80
  + 核心 submodule jeseibel/distant-horizons-core @ 64c5d96，2026-09-15
  读码摘录见 §4b）
- 硬约束（裁决，不讨论）：
  - baseline = Vulkan 2026 里程碑 + `VK_EXT_descriptor_heap`，**不做旧设备兜底**，
    设备不满足 → mod 禁用（init 检查 + 明确报错）；
  - 只做几何搬运：**不做光照**（远方无光 + 官方 fog = DH 式远景标准观感）；
  - 光栅化/雾/投影/深度全部沿用官方 26.2 Vulkan 渲染器；
  - 一条代码路径，mesher 从第一天起就是 **DAG 形状**；最终形态（S4）= **Voxy 式 GPU 体素 mesher**
  （GPU 驻留体素数据 → 设备侧 compute DAG 生成任意巨型 mesh → 同一张 heap 表 →
  官方渲染器）；S1~S3 的格阵高度场是它的**冷启动引擎与数据地基**，不是死胡同
  （后路红线见 S4 节 R-a~R-f，S1~S3 全程不得违反）。

---

## 1. 核心思路（最简单的"替换几何法"）

Distant Horizons 的内核：**世界按 32×32×32 world-section 网格划分，远方只保留
"每列表面"（per-column top）的无光剪影 mesh，LOD 靠 4×4×4 上卷合并，流式按
相机距离带构建/驱逐**。我们取其形、换其体：

- 几何：每个 world-section cell 生成一张**无光高度面**（每列一个顶点：
  顶面实方块的 y + 基色），33×33 顶点网格，约 2k 三角形/cell，float32 存
  **cell 原点相对坐标**（精度从第一天就按超远设计，见 §3.3）；
- 渲染：不碰官方地形管线。新建 `FarTerrainRenderer`（照抄官方 `CloudRenderer`
  的"独立 renderer + 独立 RenderPass"先例），shader = 官方 `terrain.vsh/fsh`
  减去光照 + 官方 `include/fog.glsl`（std140 `Fog` 块布局已核实，§2.3）；
- 搬运：**per-cell 动态 UBO 直接复用官方
  `RenderSystem.getDynamicUniforms().writeChunkSections(ChunkSectionInfo...)`**
  （`DynamicUniforms.java:61/65`；`ChunkSectionInfo(modelView, x, y, z,
  visibility, atlasW, atlasH)`，std140 布局 = mat4+float+ivec2+ivec3）。
  每个 cell 的 `modelView` = 平移到 cell 原点的矩阵——和官方每个 16³ chunk
  section 走的是**同一条传送带**（`LevelRenderer.java:600` →
  `ChunkSectionsToRender.renderGroup` → `VulkanRenderPass.drawMultipleIndexed`
  `VulkanRenderPass.java:230`，per-draw `setUniform("ChunkSection", slice)`）。
- **S1 起：per-cell 几何（VBO/IBO/cell UBO）第一天就走 `VK_EXT_descriptor_heap`**
  ——"heap 把一堆几何体上表面塞进渲染器"（2026-09-15 定）：一个 resource heap
  = 全体 cell 的几何表，per-cell 子分配，帧 command buffer 一次
  `vkCmdBindResourceHeapEXT` 绑掉整堆范围；per-frame 动态部分（visibility 淡入）
  S1 先走官方 `writeChunkSections` 通道，S2 把 UBO 也并入堆。"descriptor heap
  取代 Voxy 式 GL 状态机魔法"在此落地（per-region 绑定 = 堆子分配，写描述符 =
  写内存）。API 面已核实（`refs/README.md`）：入口 `vkCmdBindResourceHeapEXT` /
  `vkCmdBindSamplerHeapEXT`（`VkBindHeapInfoEXT`，vulkan_core.h:16987/17014）；
  描述符 = 堆内存直接映射（`VK_DESCRIPTOR_MAPPING_SOURCE_RESOURCE_HEAP_DATA_EXT`，
  GPU 读 = `VK_ACCESS_2_RESOURCE_HEAP_READ_BIT_EXT`）；堆 sizing 依据
  `VkPhysicalDeviceDescriptorHeapPropertiesEXT`（buffer descriptor size/alignment、
  resourceHeapAlignment、maxResourceHeapSize）；mod 代码走游戏随包 LWJGL 3.4.1 的
  `org.lwjgl.vulkan.EXTDescriptorHeap` 绑定。

**我们不做 DH 的**：天空剪影（sky silhouette）、天气/云 LOD、GPU-resident
体素网格（S4 前 CPU 提取足够）、shader 侧距离雾自实现（直接用官方 `Fog`）。

---

## 2. 已核实的 26.2 ABI / 钩点（全部源码引用，2026-09-15 实测）

| 项 | 事实 | 出处 |
|---|---|---|
| 动态 UBO ABI | `ChunkSectionInfo` std140 = mat4 + float + ivec2 + ivec3；`writeChunkSections(...)` 返回 `GpuBufferSlice[]`；`DynamicUniformStorage` 帧环 + `endFrame()` | `DynamicUniforms.java:23-65` |
| 官方用法 | 每 section 一条 info，`uploader.upload("ChunkSection", slice)` 进 `RenderPass.Draw` | `LevelRenderer.java:580-602` |
| draw 入口 | `drawMultipleIndexed(draws, defaultIndexBuffer, ..., "ChunkSection"→per-draw uploader, infos)`；Vulkan 实现 per-draw `setIndexBuffer/setVertexBuffer(vb.slice())/drawIndexed` + `pushDescriptors()` | `VulkanRenderPass.java:230-262` |
| fog | `include/fog.glsl` std140 `Fog`{vec4 FogColor; float FogEnvironmentalStart/End, FogRenderDistanceStart/End, FogSkyEnd, FogCloudsEnd} + `apply_fog(...)`（球/柱双距离取 max） | `assets/minecraft/shaders/include/fog.glsl:3-38` |
| transform | `include/dynamictransforms.glsl` std140 `DynamicTransforms`{ModelViewMat, ColorModulator, ModelOffset, TextureMat}——`ModelOffset` 是官方自带的"高精度平移"机制，S3 超远用 | `include/dynamictransforms.glsl:3-8` |
| 投影/远平面 | `RenderSystem.setProjectionMatrix(GpuBufferSlice, ProjectionType)`（静态 slice，per-frame 由 Camera 写入）；far 值来源 = `Camera.java:92` `max(renderDistance·16·4, cloudRange·16)`（阶段一文档）；S3 扩 far = hook 该写入调用方 | `RenderSystem.java:177-230` |
| 深度 | 官方 reverse-Z（clear 0.0、GEQUAL、near 0.05 固定、`ndcz(d)=19.997/d+0.0031` @f=320）——S0 首测项：我们的 pass 必须吃同一套深度约定 | `docs/mc26.2-vk-reference.md` §2 |
| 句柄 | probe mixin（`dev.dhvk.VkHandlesProbeMixin`）已捕获 raw `VkDevice`/`VkQueue`（`VulkanDevice.<init>` 注入）；官方 `GpuBuffer` 无 STORAGE usage flag → 未来 SSBO 走 raw handle | `mod/` 阶段一交付物 |
| descriptor heap API | `vkCmdBindResourceHeapEXT`/`vkCmdBindSamplerHeapEXT`（`VkBindHeapInfoEXT`，vulkan_core.h:16987/17014）；描述符 = 堆内存直接映射（mapping source `VK_DESCRIPTOR_MAPPING_SOURCE_RESOURCE_HEAP_DATA_EXT`=4；GPU 读 = `VK_ACCESS_2_RESOURCE_HEAP_READ_BIT_EXT`）；堆 sizing = `VkPhysicalDeviceDescriptorHeapPropertiesEXT`（buffer/image/sampler descriptor size 与 alignment、resourceHeapAlignment、maxResourceHeapSize、maxPushDataSize）；feature gate = `VkPhysicalDeviceDescriptorHeapFeaturesEXT.descriptorHeap`；LWJGL 3.4.1 `EXTDescriptorHeap` 绑定已随包（`lwjgl-vulkan-3.4.1.jar` 核实） | `refs/vulkan-headers` 1.4.357 + `refs/README.md` |
| 后端开关 | `--vulkanValidation` + `--graphicsBackend vulkan`（26.2 新 CLI，必填 `--accessToken`/`--version`，见 `docs/mc26.2-vk-reference.md` §5） | `Main.java:65-112` |

---

## 3. 数据与内存布局

### 3.1 CPU 侧 cell 注册表
- `CellKey = (ix, iy, iz)`（cell 边长 S0/S1 = 32 blocks；S2 上卷 128/512）；
- 每 cell：`{ float[] verts (相对原点), byte[] colors (RGBA8), int[] indices,
  long generation, 基色 }`；注册表 `HashMap` + 构建/驱逐预算队列；
- 数据来源：内存中已加载的 chunk sections（`Level`/`ChunkAccess` 的 block 查询），
  "顶面实方块" = 每列从顶向下的第一个非 air block（S1 简化：不处理悬空/悬崖面，
  S2 加 2×2 子采样 + 简单崖面规则）；
- chunk load/modify 事件 → cell 标记 dirty（S1 可先用"chunk 加载即重建"的粗策略）；
- **提取流从第一天起写成 DAG**（S4 后路 R-d）：阶段 = [block 数据 ready] →
  [表面提取] → [几何上传] → [表更新]；S1/S2 的执行器 = CPU，S4 的执行器 =
  GPU compute（每阶段一个 compute shader + 显式依赖）——同一张图，换引擎不换图。

### 3.2 GPU 侧 arena
- **VBO/IBO arena**：单块 `GpuBuffer`（官方 usage flag 即可，S0 核实 VBO/IIBO
  flag 名），per-cell 子区 = 偏移+大小；每帧 dirty cell 增量 `vkQueueSubmit`
  上传（走官方 transfer 队列或 probe 捕获的 raw handle——S0 二选一）；
- **per-cell 描述符**（S1 起，heap 载体）：一个 resource heap = 全体 cell
  几何表；每 cell 子区 = {VBO slice 描述符, IBO slice 描述符, cell UBO 描述符
  （modelView=cell 原点平移 / xyz / 静态部分 bake 进堆；per-frame 的
  visibility 淡入 S1 走官方 `writeChunkSections` 动态通道，S2 并入堆）}；
  帧 command buffer 一次 `vkCmdBindResourceHeapEXT`（`VkBindHeapInfoEXT`）
  绑掉整堆范围，per-cell 偏移进 draw 参数；堆大小 =
  f(驻留 cell 数, `VkPhysicalDeviceDescriptorHeapPropertiesEXT` 的
  bufferDescriptorSize/Alignment 与 resourceHeapAlignment)；
- **block 数据 tile（S4 后路 R-b，S1 起同步上传）**：每 cell 的 32×32×32
  block ID 紧凑 tile（调色板索引量化，~3~8KB/cell）→ raw VkBuffer SSBO arena
  （官方 `GpuBuffer` 无 STORAGE flag → raw handle，probe 已就位）。S1/S2 的
  CPU 提取直接读内存 chunk、不消费这块 tile，但**上传从第一天起**——S4 GPU
  mesher 需要它，避免"补数据通道"的返工；
- **heap 子区容量按 S4 最坏情况预摊**（S4 后路 R-c）：每 cell 几何子区按
  任意 mesh 预算（16~64KB 量级，init 时定）分配，S1/S2 的高度场只占其中一小
  部分——日后堆表不重建、不搬家；
- 帧预算（S1）：新构建 ≤ 8 cells/frame，驱逐 ≤ 2 cells/frame，驻留上限 8192 cells。

### 3.3 精度约定（超远 from day 1）
- 顶点 = cell 原点相对坐标（±S/2 内，float32 无损）；
- cell 原点经 `ChunkSectionInfo.modelView`（mat4 平移）进顶点着色器——32/128
  blocks 尺度下 float32 平移误差 <1cm，剪影足够；
- S3 超远（≥ 32km）：切官方 `DynamicTransforms.ModelOffset`（vec3 单独平移，
  官方自带的高精度通道）或按 512-block 超级 cell 锚点再细化。

---

## 4. 切片（每片 = 可独立验收的增量，VVL 全程开启）

### S0 — 巨型色块冒烟测试（0.5~1 天）
**目标**：证明"官方渲染器 + 我们的 pass + 官方深度/雾/投影"这条传送带通。
1. 验证项（先读后写）：官方 `terrain.vsh/fsh` 的 uniform 块与 include 结构
   （我们的 `far_terrain.vsh/fsh` 直接改自它）；`CloudRenderer` 的
   pass/pipeline 注册与生命周期（26.2 的 pass 创建走哪条路）；
   `RenderSystem.setProjectionMatrix` 的 per-frame 调用方；官方 `GpuBuffer`
   的 VBO/IIBO usage flag 枚举值；
2. 交付：`FarTerrainRenderer` 骨架（独立 `RenderPass`，`far_terrain.vsh/fsh`
   经 `mc-src/scripts/compile-spirv.sh` 工具链离线编译）+ 一块 16×16 blocks
   的纯色 quad 挂在 4000 blocks 外 + 官方 `Fog` include；
3. 验收：
   - VVL 零 error（warning 逐条过目并记录）；
   - 屏幕远处看到色块，**被近景地形正确遮挡**（reverse-Z 深度融合正确性首证）；
   - 色块随距离吃上官方雾（证明 `Fog` uniform 接线正确）；
   - 与原版基线 diff（等用户那次 vanilla 启动日志）：帧提交方式不变
     （fenceless `vkQueueSubmit2KHR`）、队列使用不变、仅多一条 pass 录制。

### S1 — 把高度面 cell 用 heap 塞进官方渲染器（2~3 天）
**目标**：远方地平线出现无光地形剪影；几何走 descriptor heap 通道（机制
即用户定调的最小形态：heap 把一堆几何体上表面塞入渲染器）；流式不掉帧。
1. **设备硬门槛（基线裁决的落地代码）**：init 检查 2026 里程碑 feature 集
   + `VkPhysicalDeviceDescriptorHeapFeaturesEXT.descriptorHeap` + VRS（2026
   core），缺一即明确日志禁用 mod（**不兜底**）；
2. **descriptor heap 载体**：建一个 resource heap（大小按 §3.2 计算）；
   per-cell 子分配 = {VBO, IBO, cell UBO} 描述符写入堆内；帧录制时一次
   `vkCmdBindResourceHeapEXT`；per-frame visibility 淡入 S1 暂走官方
   `writeChunkSections` 动态通道（S2 并入堆，见下）；
3. **VRS（2026 里程碑核心，设备保证）**：far band pass 设 2×2 coverage rate，
   一个 pass 级设置，无代码分支；
4. `FarTerrainRenderer` 完整帧循环：相机位置 → 距离带
   `[bandNear=512, bandFar=3072]` blocks 内 cell 的帧剔除（cell AABB + 世界
   Y 带，DH `DhFrustumBounds` 的 FrustumIntersection 姿势）→ 可见集（目标 ≤
   2000 cells）→ `drawMultipleIndexed`（heap 绑定在 pass 级完成）；
5. 流式：帧预算构建/驱逐（§3.2），LRU 驱逐；chunk 事件 → dirty 重建；
6. 验收：
   - 站在平原：远方地平线被无光地形剪影填满，剪影边缘溶进官方雾；
   - heap 路径下 VVL 零 error；`latest.log` 与基线 diff 干净；
   - 把 descriptor heap feature 摘掉（测试 build）→ mod 以明确日志禁用
     （验证"不兜底"）；
   - RTX 5090：3km 距离带下 +1ms/帧以内（F3 + 简单计时）；
   - 走动/转头无 pop-in 爆闪（淡入时长 ≥ 官方 chunk fade 同款）。

### S2 — LOD 阶梯 + 遮挡剔除 + shader clock 遥测 + VRS 细化（3~5 天）
**目标**：距离带拉到 10km+ 仍稳；per-LOD 预算由 GPU 侧遥测驱动，不再手调。
1. LOD 上卷：parent cell(128/512/2048) = 4×4（x/z 二维）子 cell 列顶面的
   子采样合并（DH `LodUtil` 是 ×2/级 64→128→256→512；我方 ×4/级、格子少、
   机制更简单）；每级独立 shader 变体不需要——同一 shader，LOD 只是
   cell 边长参数；
2. **构建期列间遮挡剔除（DH `FullDataOcclusionCuller` 姿势）**：相邻列（±X/±Z
   视线判定）有更高点则剔除被挡点——山地三角形数大幅下降，运行时零成本；
3. **shader clock queries（KHRShaderClock，LWJGL 绑定已随包）**：far pass
   前后夹一对 start/end shader clock query → 每帧 GPU 侧代价遥测 →
   驱动 per-LOD 构建/驱逐预算与距离带半径的自动调节；
4. VRS 细化：per-LOD rate map（近 LOD 1×1 / 远 LOD 2×2 / 极远 4×4）
   （2026 里程碑 = VRS core，设备保证，无 feature 分支）；
5. 流式按 LOD 分级预算（近级预算大、远级预算小），驱逐滞后带防抖动；
   per-cell UBO（含 visibility）并入 heap，官方动态通道退役；
6. 验收：10km 距离带 5090 上 +2ms/帧以内；VVL 零 error；shader clock
   遥测有输出（log/调试屏，数值随 LOD 变化合理）；VRS 细化生效证据
   （validation 日志 / 采样率 dump，S2 内定一种）。

### S3 — 远平面扩展 + 边界打磨（2~3 天）
1. 扩 far：hook `RenderSystem.setProjectionMatrix` 的调用方（Camera 侧），
   以 `max(官方far, bandFar·k)` 重建投影；**官方 ndcz 映射
   `19.997/d+0.0031` 是 f=320 的产物，far 变了要重新推导**（reverse-Z 的
   ndcz(d) 解析式照 `docs/mc26.2-vk-reference.md` §2 的方法重算并写入文档）；
2. 边界打磨：`Fog` uniform 的 `FogRenderDistanceEnd` 随我们的 bandFar 扩展；
   我方 fsh 加距离 alpha ramp（剪影 → 雾色渐变，消 DH 式"地平线硬边"）；
3. 超远精度切换：≥32km 段启用 `ModelOffset` 通道（§3.3）；
4. （可选，DH 参照）`uEarthRadius` 式地曲率视觉项——超远 render distance 下
   地平线"下凹"的世界曲率观感（DH terrain shader 已含此项，代码量很小，
   S3 有余力则加，否则顺延 S4）；
5. 验收：10km/20km 距离带截图对比；深度精度抽查（极远处 cell 边缘无抖动/
   无 z-fight）；官方近景光照/雾完全不受影响（与基线 diff 干净）。

### S4 — Voxy 式 GPU 体素 mesher（正主）+ Caustica RTAS 出货口（后置，~1 周起步，另立计划）

> **2026-09-16 pivot 裁定（重定义）**：本章"Voxy 式 **GPU** 体素 mesher"为对 Voxy 真实架构的
> 误读（其网格化 = CPU 多线程**二进制贪心**，GPU 只驱动渲染侧；用户 2026-09-16 下载汇总核实，
> 要点已摘录进规格）。S4 重定义为 **留门 + 出货口**：① `CellMeshExtractor` 第三实现位
> （GPU compute / shader enqueue，只冻 seam 不写码）② Caustica RTAS 出货口经
> `CellGeometryObserver`（登记接口，不研究 BLAS）。"真体素几何"正主提前至 S2 修订
> （CPU 贪心 + 真贴图 + 烘焙 AO，高度场退役）。下方原纲要保留作历史；R-a~R-f 红线表由
> 规格 §5 v2 取代。全文：`docs/superpowers/specs/2026-09-16-greedy-mesh-pivot-design.md`。

**定位（2026-09-15 用户定调："这才是我想要的 voxel 的办法，类似 Voxy"）**：
S1~S3 的格阵高度场是**冷启动**——先让传送带跑通、让 heap 表立起来、让数据
地基（block tile + 注册表 + arena）就位；S4 把几何生产者从"CPU 高度场提取"
换成 **Voxy 式 GPU 体素 mesher**：GPU 驻留的体素数据 → 每 LOD 一个 compute
shader 的**设备侧 DAG**（显式依赖）→ **任意三角形几何**（不再是高度场：树、
崖壁、悬挑、洞穴开口都能出来）→ 写进**同一张 heap 表** → 走**同一条官方
渲染通道**。渲染侧从 S1 到 S4 一行不变（同顶点格式 position+color 无光、
同 shader、同 heap 表、同 draw 路径）——官方渲染器永远只知道"表里有多了
更精致的几何"，不知道几何是从高度场升级来的。这正是"descriptor heap 取代
Voxy 的 GL 魔法"的完整形态：Voxy 的 GPU 驱动 mesher 思路 + 我们的 26.2 官方
渲染器 + 2026 基线。

**后路红线（S1~S3 全程不得违反，违反即返工）**：

| # | 红线 | 说明 |
|---|---|---|
| R-a | cell 注册表/heap 表是几何唯一权威，渲染器只消费"cell → (几何子区, UBO)" | 渲染通道对几何**来源无感知**（CPU 提取或 GPU mesher 均可） |
| R-b | 每 cell 的 block 数据 tile（32³ 量化索引）从 S1 起同步上传 GPU（raw handle SSBO） | S4 GPU mesher 的输入，禁止"先不传、S4 再补通道" |
| R-c | heap 每 cell 子区容量按 S4 任意 mesh 最坏预算在 init 预摊 | 日后堆表不重建不搬家 |
| R-d | mesher 接口 = DAG（数据 ready → 提取 → 上传 → 表更新），S1/S2 = 该 DAG 的 CPU 执行器 | S4 只换执行器（GPU compute / 未来设备侧 enqueue），不换图 |
| R-e | 顶点格式（position + 基色，无光）不变 | S4 任意三角汤走同一 pass/shader/heap |
| R-f | 任何 ABI 不得 bake"单层天空线"假设 | S2 多层点列表已是其超集，S4 任意 mesh 是进一步超集 |

**S4 实施纲要（另立计划细化）**：
- GPU 体素 mesher：以 R-b 的 block tile 为输入，compute shader 做完整表面提取
  （2026 里程碑 **compute shader derivatives** 的正主：dFdx/dFdy 做边缘/崖面
  检测，替掉 CPU 启发式）；每 LOD 阶段 = 一个 compute shader + 显式依赖 =
  DAG 正式形态，执行器 seam 为 working group / working group（shader enqueue /
  cooperative vector，LWJGL 绑定已就位）预留；
- culling 上 GPU：per-cell 可见性（视锥 + 距离带 + 遮挡）从 CPU 循环迁到
  GPU 侧（device-driven），与 mesher DAG 串联；
- "一份 mesher 两个出货口"：cell 几何直接喂 `refs/caustica/` 的
  `rt/terrain` 链（`RtTerrainMesher`/`RtSectionBuilder`/`RtAccel` → RTAS），
  使硬件光追视角与官方光栅远景共用同一份几何；
- RTAS 接口：cell 注册表即几何唯一权威，RTAS 重建 = 注册表 diff →
  `vkBuildAccelerationStructure`；
- （可选，DH 路线）"加载范围之外"的超远数据：DH 的 mimic 世界生成器
  （`DhInternalServerGenerator`）+ 磁盘 full-data 缓存（`FullDataSourceV2`）
  预生成路线——届时才考虑自研文件格式；
- 前置：用户裁决"依赖其 API / 参照 / fork"三档（现定 = 参照，见 README）。

---

## 4a. VK2026 里程碑采用矩阵（2026-09-15 定调：尽可能用，但只采用
零/低机制成本的用法）

| VK2026 条目 | 落点 | 用法 | 机制成本 |
|---|---|---|---|
| `VK_EXT_descriptor_heap` | S1（载体）/ S2（规模化） | 全体 cell 几何表 = 一个 resource heap，per-cell 子分配，帧内一次 bind | 零——替代 per-region 绑定风暴，正是裁决本体 |
| variable rate shading（2026 core） | S1（2×2 远带）/ S2（per-LOD rate） | pass 级 coverage rate，设备保证支持 | 零——一个 pass 设置，无代码分支 |
| shader clock queries | S2 | far pass GPU 侧每帧代价 → 自动调 LOD 预算/距离带 | 低——两个 query 调用 + 遥测输出 |
| host image copies | S3（可选） | cell 元数据若转 image-tile 形态，CPU 零拷贝更新（省 transfer 流量） | 低——备选路线，不在关键路径 |
| compute shader derivatives | S4 | GPU 高度场 mesher 的 dFdx/dFdy 崖面/边缘检测（替 CPU 启发式） | 属 DAG mesher 本体 |
| swapchain 改进 / 接口上限提高 | S0 起继承 | 官方渲染器领地；提高的 descriptor/shader 接口上限 = heap 表扩展到数万 cell 的 headroom | 零——白拿 |
| shader enqueue / cooperative vector（working group，vendor 提案，未入 2026 里程碑） | S4 后 | DAG 执行器 seam（设备侧 mesher 执行） | 只预留，不写码 |

---

## 4b. DH 参照系要点（refs/dh，2026-09-15 读码摘录）

Distant Horizons（GitLab 主仓库 + 私有但可公开拉取的 core submodule；
LGPL-3.0；**支持 MC 26.2**，与我们同代代）的核心机制与我们方案的对照：

| DH 机制（出处） | 事实 | 我方取舍 |
|---|---|---|
| 2D LOD 高度场网格（`DhSectionPos.java:61-73`） | x/z 分格、无 y 维；64bit 打包位 = detail(8) + x(28) + z(28)（世界范围 ±128M blocks）；`SECTION_MINIMUM_DETAIL_LEVEL=6` → 最小 64×64 blocks，×2/级，region = dl9 = 512×512（`LodUtil`） | **采其形**：同为 2D 高度场；我方 ×4/级（32→128→512→2048），格子更少、机制更简单 |
| 每列数据点 + 列间遮挡剔除（`FullDataOcclusionCuller.java`） | 数据点 = long 打包（高度+颜色+detail）；±X/±Z 相邻列视线判定，构建期剔除被挡点 | **采**（S2）：山地三角形数大降，运行时零成本 |
| 帧剔除（`DhFrustumBounds.java`） | joml `FrustumIntersection` + 世界 Y min/max 带 | **采**（S1）：cell AABB × Y 带同款姿势 |
| 高精度平移（terrain `vert.vert`：`uniform vec3 uModelOffset`） | 与官方 `DynamicTransforms.ModelOffset` 同一招 | 印证 §3.3 三段式精度约定（官方/DH/我方三方同构） |
| 自渲染管线（`LodRenderer`：离屏渲染目标 + 后处理 fog/fade/TAA/SSAO/apply；openGl 与 sodium/blaze 双后端） | DH 不依赖 vanilla 渲染器，整套自建 | **不跟**（裁决：渲染交官方 26.2 Vulkan 渲染器）——这正是我们与 DH 的本质差异 |
| 预生成 + 磁盘缓存（`DhInternalServerGenerator`/`DhChunkGenerator`/`DhRoughSurfaceGenerator` 的 mimic 世界生成 + `FullDataSourceV1/V2` 文件格式） | 远方地形由内置生成器模拟生成并落盘，不读内存 chunk | **MVP 不跟**（实时提取已加载 chunk，无文件格式）；S4 可选再议（"加载范围之外"的超远数据） |
| shader 彩蛋（`uEarthRadius` 地曲率、dither/TAA jitter、lightmap 采样） | 超远 render distance 下地平线下凹的曲率观感 | S3 可选项（代码量小，有余力则加） |

---

## 5. 风险与开放问题（S0 逐项销账）

| # | 风险/问题 | 处置 |
|---|---|---|
| R1 | 26.2 的 pass/pipeline 注册生命周期与旧版不同（`CloudRenderer` 先例是否仍成立） | S0.1 读码确认；不行则走 `VulkanRenderPipeline` 直建（probe 句柄在手） |
| R2 | 官方 `terrain.vsh` 对 lightmap/光照的耦合比预想深（剥不干净） | 退路：我方 fsh 只保留 `#include <fog>` + 自写 5 行，vsh 剥光照——S0.1 读完即定 |
| R3 | `GpuBuffer` 无 STORAGE flag → SSBO 只能 raw handle（未来 compute mesher 的地基） | 已核实（阶段一）；S4 前不阻塞，probe 句柄已就位 |
| R4 | 官方 `setProjectionMatrix` per-frame slice 的重建成本 / hook 点稳定性 | S3 前只读不写（用官方 far）；S3 hook 若不稳，退路 = 我方 pass 自带投影 UBO（与官方数值对齐，`ndcz` 重算保证深度一致） |
| R5 | chunk 未加载区域（超远）没有 block 数据可提取 | DH 同款答案：LOD 上卷 + 已加载区的"最后一次数据"（cell 驱逐滞后带内保留数据）；S1 接受"加载过才有"，S2 滞后带消 pop |
| R6 | float32 超远精度 | §3.3 三段式（相对坐标 → mat4 平移 → ModelOffset 锚点），S3 验证 |
| R7 | 用户 vanilla 基线日志未到手（启动中） | S0 验收的 diff 参照；到即补跑 |

---

## 6. 执行顺序与当前状态

1. [等待用户] 宿主跑通 `run-vanilla-vk.sh`（vulkan-validation-layers 已装，
   脚本已修 CLI 契约 + Fedora explicit_layer.d 探测位，commit 43340cf）→
   核对 GPU=RTX 5090 / 三队列 / Submit2 → 基线入档；
2. S0（含 §5 验证项）→ S1（heap 载体 + VRS + 硬门槛）→ S2（LOD 阶梯 +
   遮挡剔除 + shader clock 遥测 + VRS 细化）→ S3（远平面 + 边界打磨）
   → S4（**Voxy 式 GPU 体素 mesher DAG** + RTAS 出货口；S1~S3 全程背负
   S4 后路红线 R-a~R-f）（每片收尾：VVL 零 error + 基线 diff +
   截图 + 一段 ≤5 行的"本片中招清单"）；
3. 每片代码进 `mod/`（fabric-loom 脚手架已备），shader 走
   `mod/scripts/compile-spirv.sh`（glslang 16.2.0 宿主已装）；
4. 全程 `todo_write` 切片进度跟踪；文档随片更新本文件（验收结果回填）。

**当前状态（2026-09-15 回填）**：
- S0 验收通过 @`94853f7`（标准档 + 全开档 VVL 零报错）；
- S1 全部闭合 @`4bd9acc`（run45-50 目验裁决；heap 载体 / VRS / 硬门槛 / 全带流式
  闸门全过；"超远 -400 正面远观"与全带剪影视觉由 S2 承接）；
- S2 规格已定稿：`docs/superpowers/specs/2026-09-15-s2-lod-ladder-design.md`
  （用户 2026-09-15 裁定：五件顺序 秒表→LOD 阶梯→遮挡剔除→VRS rate map→
  远平面三件套，每件独立闸门；S2/S3 边界 = A：远平面 hook + fog end 扩展 +
  ndcz 重推导焊进 S2，S3 只留打磨）；步骤 1 任务 0 勘察收口：秒表机制裁决
  = 官方 timeline semaphore 搭车 + 官方公开 `writeTimestamp`/`createTimestampQueryPool`
  （见 `docs/s2-code-reading-notes.md` §1~§2）；
- S2 步骤 1（GPU 秒表）✅ 闭合 @`68278e1`（run1-4：机制全链通 + VVL 标准档/全开档
  双零 + 目视"还是 S1 那张脸"用户裁决 + 干净退出；自校准 ≈1GHz/tick（NVIDIA 5090
  驱动自报 period=1/validBits=64 为名义值，vulkaninfo 对拍）→ far pass 实测
  6~21µs（1ms 门下富余 50~120 倍）；验收报告
  `docs/s2-acceptance/step1-gpu-stopwatch.md`，日志 `docs/s2-acceptance/logs/`）。
