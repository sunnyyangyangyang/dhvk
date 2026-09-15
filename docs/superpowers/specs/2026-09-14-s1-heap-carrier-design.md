# S1 设计规格：DH 几何搬运工 —— descriptor heap 载体

- 日期：2026-09-14
- 状态：设计对话完成，用户批准（四节设计 + A+C 验收口径，2026-09-14）
- 依据：
  - `docs/superpowers/plans/2026-09-15-phase2-dh-geometry-hauler.md`（阶段二 MVP 计划：§1 核心思路 / §3 数据与内存布局 / §4 S1 切片 / §4a VK2026 采用矩阵 / §5 风险 / S4 红线 R-a~R-f）
  - 阶段一 spec `docs/superpowers/specs/2026-09-13-dh-vk2026-env-design.md` §7 原则
  - S0 验收记录 `docs/s0-acceptance/2026-09-14-s0-accepted.md`（HEAD `94853f7`，标准档+全开档 VVL 零报错）
  - 用户裁决：baseline = vk2026 里程碑 + `VK_EXT_descriptor_heap`，**不做旧设备兜底**；"descriptor heap 代替 voxy 的 gl 魔法"；"机制尽可能简单，heap 把一堆几何体上表面塞入渲染器"；S4 后路（R-a~R-f）S1~S3 全程不得违反
- API 参考基线：`refs/vulkan-headers`（KhronosGroup/Vulkan-Headers @ vulkan-sdk-1.4.357.0）+ 游戏随包 LWJGL 3.4.1（`mc-src/artifacts/libs/lwjgl-vulkan-3.4.1.jar`，EXTDescriptorHeap 类面已核实随包）

## 0. 范围与非目标（S1 = 机制切片，验收口径 A+C）

**目标**：把"descriptor heap 载体"在官方 26.2 渲染器里跑通——一个持久 resource heap 持有全体 cell 的几何表（VBO/IBO 描述符 + 静态 cell 数据），每帧一次 `vkCmdBindResourceHeapEXT`，pass 级 VRS 2×2，设备硬门槛落地代码，距离带 [官方雾起点, 官方 depthFar] 内的全带流式机制（合成调试环承载机制验证），真实数据薄环作为观察项。

**非目标（S1 不做，留给后续切片）**：LOD 上卷与遮挡剔除（S2）、shader clock 遥测（S2）、per-cell UBO 并入堆（S2）、远平面扩展与边界打磨（S3）、GPU mesher（S4）。"剪影溶进雾"的**视觉**验收顺延 S2；4000 块领地顺延 S3。

## 1. 验收标准（机制门）

| # | 验收项 | 门级 |
|---|---|---|
| 1 | 硬门槛正路径：`descriptorHeap` feature 在官方设备上真实置位（设备创建日志/查询确认 + VVL 干净） | 硬 |
| 2 | 硬门槛负路径：测试 build 摘掉设备手术 → 明确日志 + mod 自禁用（帧图不挂 pass、不建缓冲），游戏照常干净运行、退出 0（"不兜底"实证） | 硬 |
| 3 | heap 载体：全体 cell 几何表 = 一个 resource heap，每帧一次 `vkCmdBindResourceHeapEXT`；标准档 + 全开档（sync + gpu_assisted + best_practices）VVL 双零 | 硬 |
| 4 | VRS pass 级 2×2 coverage 生效且 VVL 干净 | 硬 |
| 5 | 合成调试环全带跑通：距离带 [官方雾起点, 官方 depthFar]（RD32 = [448, 2048]）；流式预算（帧建 ≤8 / 帧驱逐 ≤2、LRU、驻留 8192）在全带内运转；5090 上 +1ms/帧以内（F3 + 简单计时）；无 pop-in（淡入 ≥ 官方 chunk fade 时长） | 硬 |
| 6 | 与 vanilla 基线（`docs/baselines/`）日志 diff 干净（帧提交方式/队列使用不变，仅多 pass 录制与设备扩展） | 硬 |
| 7 | 真实数据薄环（已加载 chunk 提取的 cell）出图且被近景深度遮挡 | 观察项（不计成败） |

## 2. 设备手术与硬门槛

### 2.1 已核实事实（26.2 官方树，`mc-src/client/`）

- `VulkanBackend.REQUIRED_DEVICE_EXTENSIONS`（L55）= 5 个固定扩展（dynamic_rendering / push_descriptor / synchronization2 / vertex_attribute_divisor / swapchain），**无 descriptor heap**；
- 设备创建路径：`createDevice` 内本地扩展列表（multi_draw 条件加入，L160-161）→ `enabledExtensionsBuffer`（L422-432）→ `vkCreateDevice` 的 `pNext(deviceFeatures.pNext())`（L430）；
- feature 查询/创建共用 `VulkanPNextStruct` + `REQUIRED_DEVICE_FEATURES` 机制（L65-108），结构体集合 = {VK10, VK11, VK12, SYNC2, DYNAMIC_RENDERING, VERTEX_ATTRIB_DIVISOR, MULTI_DRAW}；
- 探针现状：`VkHandlesProbeMixin`（`VulkanDevice.<init>` RETURN）；S0 日志 "vk handles: not captured yet" 出自 mod 启动时刻（设备尚未创建），且 `VkHandles.capture()` 成功时**无确认日志** → 悬案大概率是虚惊。S1 第一步：`capture()` 补确认日志（走 `DhVkClient` 共享 logger），首跑 VVL 一验；若真未触发按 §7 R6 重新布探针。

### 2.2 手术（两个小 mixin，S0 验证过的姿势）

- **mixin ① 设备扩展**：`VulkanBackend.createDevice` 的本地扩展列表上加 `VK_EXT_descriptor_heap`——**条件式**（物理设备声明了该扩展才加，官方 multi_draw L160 同款姿势）。驱动没有 → 不加 → 设备创建照常成功 → mod init 检查报禁用。游戏不在别的 GPU 上崩，mod 门槛干净。
- **mixin ② feature pNext 链**：注册 `VulkanPNextStruct(1000135009, VkPhysicalDeviceDescriptorHeapFeaturesEXT.SIZEOF)`（`VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT` = 1000135009，1.4.357 头已核实）+ `REQUIRED_DEVICE_FEATURES` 加 `descriptorHeap` 位 → 查询路径（`vkGetPhysicalDeviceFeatures2`）与创建路径（`vkCreateDevice` pNext）**同时落地**。

### 2.3 硬门槛逻辑（不兜底）

mod init（设备就绪后）：`descriptorHeap` 未置位 → 明确日志（`[dhvk] descriptor heap unavailable → mod disabled, vanilla renderer running`）+ mod 禁用（帧图不挂 pass、不建任何缓冲/堆、不跑流式循环）；置位 → 继续 S1 全部机制。

### 2.4 VRS（2026 里程碑 core，设备保证，无 feature 分支）

- 官方 `RenderPipeline.Builder` 无 VRS 方法（已核实，官方树内无任何 VRS/coverage 面）→ 我们的 pipeline 创建需挂 VRS 状态 pNext（1.4.357 头中有 NV 形态 `VkPipelineCoverageModulationStateCreateInfoNV`；2026-core 形态为读码先决 #5）；2×2 coverage 一个 pass 级设置；
- 若官方创建路径无 pNext 注入点 → 退路：捕获句柄上自建该 pipeline（只 pipeline 走 raw，其余全官方），决策写进实施计划；**自建 pipeline 必须完整复制官方 `VkPipelineRenderingCreateInfo`（dynamic rendering + 主目标颜色/深度格式 + view mask——读官方 `createRenderPass` 实现抄值），与官方 render pass 对象保持 render pass 兼容（VVL 核验）；render pass 本身仍走官方（S0 模板），只有 pipeline 走 raw**。

## 3. descriptor heap 载体机制（本体）

### 3.1 已核实 API 面（1.4.357；代码以随包 LWJGL 3.4.1 为权威）

- **"堆"不是对象，是一段持有描述符 payload 的 GPU 内存范围**：
  - 帧绑定：`vkCmdBindResourceHeapEXT(VkCommandBuffer, const VkBindHeapInfoEXT*)`
    `VkBindHeapInfoEXT { sType=VK_STRUCTURE_TYPE_BIND_HEAP_INFO_EXT(1000135003), pNext, VkDeviceAddressRangeEXT heapRange, VkDeviceSize reservedRangeOffset, VkDeviceSize reservedRangeSize }`
  - 描述符写入：`vkWriteResourceDescriptorsEXT(device, resourceCount, const VkResourceDescriptorInfoEXT*, const VkHostAddressRangeEXT*)`
    `VkResourceDescriptorInfoEXT { sType, pNext, VkDescriptorType type, VkResourceDescriptorDataEXT data }`；`data` 联合体含 `pAddressRange`（`VkDeviceAddressRangeEXT`）→ **buffer 描述符 = 纯设备地址区间，无 VkBuffer 对象引用**（"写描述符 = 写内存"的落地形态）
  - GPU 读访问位：`VK_ACCESS_2_RESOURCE_HEAP_READ_BIT_EXT = 0x4000000000000000`
- **"堆源"声明**（per set/binding）：`VkDescriptorSetAndBindingMappingEXT { sType, pNext, descriptorSet, firstBinding, bindingCount, VkSpirvResourceTypeFlagsEXT resourceMask, VkDescriptorMappingSourceEXT source, VkDescriptorMappingSourceDataEXT sourceData }`，经 `VkShaderDescriptorSetAndBindingMappingInfoEXT { mappingCount, pMappings }` 批量挂接；
  - mapping source 枚举（1.4.357）：`HEAP_WITH_CONSTANT_OFFSET_EXT=0` / `HEAP_WITH_PUSH_INDEX_EXT=1` / `HEAP_WITH_INDIRECT_INDEX_EXT=2` / `..._ARRAY_EXT=3` / `RESOURCE_HEAP_DATA_EXT=4` / `PUSH_DATA_EXT=5` / `PUSH_ADDRESS_EXT=6` / `INDIRECT_ADDRESS_EXT=7` / `HEAP_WITH_SHADER_RECORD_INDEX_EXT=8` / `SHADER_RECORD_DATA_EXT=9` / `SHADER_RECORD_ADDRESS_EXT=10`；
  - `sourceData.constantOffset = VkDescriptorMappingSourceConstantOffsetEXT` —— **per-draw 经 push constant 推送的偏移**（per-cell 寻址通道）；
- **sizing/子分配依据**：`VkPhysicalDeviceDescriptorHeapPropertiesEXT`（samplerHeapAlignment / resourceHeapAlignment / maxSamplerHeapSize / maxResourceHeapSize / min*ReservedRange / samplerDescriptorSize / imageDescriptorSize / bufferDescriptorSize / *Alignment / maxPushDataSize / sparseDescriptorHeaps / ...）；
- **feature**：`VkPhysicalDeviceDescriptorHeapFeaturesEXT { descriptorHeap, descriptorHeapCaptureReplay }`（sType=1000135009）；
- **LWJGL 3.4.1 随包已核实**：`EXTDescriptorHeap`（函数组）、`VkBindHeapInfoEXT`、`VkResourceDescriptorInfoEXT` / `VkResourceDescriptorDataEXT`、`VkDeviceAddressRangeEXT`、`VkHostAddressRangeEXT` / `VkHostAddressRangeConstEXT`、`VkPhysicalDeviceDescriptorHeapFeaturesEXT` / `...PropertiesEXT`、`VkCommandBufferInheritanceDescriptorHeapInfoEXT`（全含 `$Buffer` 变体）；
- **S1 不用 `vkCmdBindSamplerHeapEXT`**（无光 = 无纹理 = 无 sampler）；
- **官方顶点/索引绑定 = 经典调用**：`VulkanRenderPass.setVertexBuffer/setIndexBuffer`（L173-188）走 `VK12.vkCmdBindVertexBuffers/vkCmdBindIndexBuffer` → 我们的几何集必须声明为**堆源描述符**（这正是本切片的外科手术本体）；官方 `drawIndexed` 后端无关，可原样复用。

### 3.2 架构：持久堆 + 每帧绑定

- **堆内存**：设备 init 通过硬门槛后一次性分配（单一内存块，device-visible；CPU 写入路径按读码先决 #3 定：`vkWriteResourceDescriptorsEXT` 或 host-visible 直接映射）；基址 = 堆基址（`vkGetMemory/BufferDeviceAddress` 途径按 #3/#4 定）；
- **子分配**：每 cell 描述符槽 = {VBO 描述符, IBO 描述符}，按 `bufferDescriptorSize/bufferDescriptorAlignment` 对齐；**per-cell 子区容量按 S4 最坏预算在 init 预摊（R-c：几何子区 16~64KB 量级 + 描述符）**——堆表此后不重建、不搬家；
- **堆大小** = f(驻留上限 8192, properties)；`maxResourceHeapSize` 在 5090 init 实测，不足则降驻留上限（运行时预算，非设备兜底，见 §7 R4）；
- **每帧**：`vkCmdBindResourceHeapEXT` 一次绑整表范围（`reservedRange*` 参数按读码先决 #6 定，S1 倾向 0）；
- **更新**：只对 dirty cell 重写描述符 payload（cell 构建/驱逐时），几何数据走 arena 上传通道（§3.5）。

### 3.3 per-cell 绘制模式（机制 = 用户定调的最小形态）

- **set layout**：自定义"几何" bind group——binding 0 = VBO（arena 子区地址描述符），binding 1 = IBO（同）；两 binding 的 mapping source = `HEAP_WITH_CONSTANT_OFFSET_EXT`，constant offset 指向 push constant 槽 = **per-cell 描述符表偏移**（"per-cell 偏移进 draw 参数"——计划原文的落地）；
- **per-cell draw**：push cell 表偏移（push constant）+ 官方 `setUniform("ChunkSection", cellInfoSlice)`（`ChunkSectionInfo.modelView` = cell 原点平移矩阵，走官方 `writeChunkSections` 动态通道——与官方 chunk section 同一条传送带；S2 并入堆后退役）+ `drawIndexed(cellIndexCount, 1, 0, 0, 0)`（firstVertex/firstIndex = 0，子区寻址全由描述符完成）；
- **顶点格式**：position（cell 原点相对坐标）+ color RGBA8，无光（R-e 不变）；
- **shader**：S0 `far_terrain.vsh/fsh`（#moj_import dynamictransforms/projection/fog + `apply_fog` 8 参）+ per-cell modelView 通道（官方 `terrain.vsh` 的 ChunkSection 同源机制）+ 2026 式 VBO 描述符顶点输入声明（语法按读码先决 #1，以官方 26.2 shader 的顶点输入声明为模板，现有 glslang 16.2 工具链离线编译验证）；
- **pipeline BGL 集合** = {GLOBALS, MATRICES_PROJECTION, FOG}（S0 继承）+ 几何 BGL；opaque 混合、`DepthStencilState.DEFAULT`（reverse-Z）、cull off——S0 已验证全保留。

### 3.4 读码先决（S0 打法：先读后写，实施第一步，全部有落点）

| # | 问题 | 读什么 | 产出 |
|---|---|---|---|
| 1 | 2026 pipeline 中堆源 VBO/IBO 绑定的完整声明：shader 顶点输入语法 + `VkShaderDescriptorSetAndBindingMappingInfoEXT` 挂接点（哪个 create info 的 pNext：set layout / pipeline layout / 两者）+ **顶点输入 ABI 形状裁决**（经典 `OpVariable Input` + 输入装配重定向，还是 BDA/拉取式——设计倾向：descriptor heap 的 VBO/IBO 是描述符但顶点输入状态保持经典，BDA 拉取属 storage buffer 范畴；规范文本 + VVL 终裁） | 26.2 反混淆树（`VulkanRenderPipeline` 的 set layout/pipeline layout 构建、`BindGroupLayout` 数据面）+ 官方 shader 顶点输入声明 + **官方 shader SPIR-V 字节码/顶点输入状态（确认 26.2 这一代顶点输入 ABI 形状）** + refs registry XML（descriptor heap 规范正文） | 自定义 BGL 声明代码 + shader 顶点输入语法 + 顶点输入 ABI 形状裁决记录 |
| 2 | 官方 command encoder 暴露 raw `VkCommandBuffer` 的途径（`vkCmdBindResourceHeapEXT` 的发射时机点 = pass 内、pipeline 绑定后、draw 前） | `GpuDevice.createCommandEncoder` / `VulkanCommandEncoder` 字段面 | 绑定发射点代码（不够则加小探针，S0 战术） |
| 3 | 堆内存 CPU 写入路径语义：`vkWriteResourceDescriptorsEXT`（host 内存参数 → 驱动写向何处）vs 直接 host-visible 映射；堆内存所需 memory 属性（device-visible 是否必须 host-visible / coherent） | refs registry XML 的 spec 文本 + VVL 行为 | 堆内存分配与写入代码（init 时枚举 `VkPhysicalDeviceMemoryProperties`，**优先 `DEVICE_LOCAL+HOST_VISIBLE`（5090 ReBAR 窗口）**；查 `HOST_COHERENT` 标志——coherent 则写后免 flush，否则显式 `vkFlushMappedMemoryRanges`；纯 device-local → CPU-visible staging + 拷贝，垫底方案） |
| 4 | 官方 `GpuBuffer` 的设备地址暴露（arena 的地址区间描述符需要 `VkDeviceAddressRangeEXT` 的 deviceAddress 字段） | `GpuBuffer`/`VulkanGpuBuffer` 字段面 | arena 地址获取；退路 = 捕获句柄上自建 buffer + `vkGetBufferDeviceAddress` |
| 5 | VRS 2026 core 的具体形态（结构体名/创建入口）与官方 pipeline 创建的 pNext 注入点 | refs registry + `VulkanRenderPipeline` 创建代码 | VRS 声明代码（退路 = raw 自建 pipeline，§2.4） |
| 6 | `VkBindHeapInfoEXT.reservedRangeOffset/Size` 语义（S1 传 0 是否合法） | refs registry XML | bindInfo 参数定稿 |

### 3.5 几何数据与 block tile

- **arena**：单一 device-local buffer 承载全体 cell 的 VBO/IBO 子区（偏移+大小入注册表）；官方 `GpuBuffer` usage flag 已验证够用（S0：VERTEX/INDEX）则直接用，否则 raw handle 自建（退路同 #4）；per-dirty-cell 增量上传（官方 transfer 通道或捕获句柄的 raw queue，读码后定）；
- **block tile（R-b，S1 起同步上传）**：每 cell 32×32×32 block-ID 量化 tile（~3-8KB）→ raw handle SSBO arena（官方 `GpuBuffer` 无 STORAGE flag → 捕获 `VkDevice` 上 `vkCreateBuffer`/`vkBindBufferMemory`）；S1/S2 的 CPU 提取不消费它——S4 GPU mesher 的输入，**禁止"先不传、S4 再补通道"**；
- **mesher 接口 = DAG（R-d）**：[block 数据 ready] → [表面提取] → [几何上传] → [表更新]；S1 执行器 = CPU（接口形状固定，S4 只换执行器——GPU compute/未来设备侧 enqueue——不换图）。

## 4. 帧循环：cell 注册表 / 剔除 / 流式

- **cell 注册表（R-a：几何唯一权威）**：`CellKey = (ix, iz)`（S1 cell 边长 = 32 blocks；高度信息 = 每列数据点，存 cell 内）；每 cell = {VBO 子区偏移+大小, IBO 子区偏移+计数, 描述符表偏移, 基色, generation, 驻留状态}；纯数据结构——渲染器只消费"cell → (几何子区, UBO)"，对几何**来源**无感知（CPU 提取或 GPU mesher 均可）；
- **数据来源**：内存中已加载 chunk sections 的表面提取（每列自顶向下首个非 air block；S1 简化：不处理悬空/崖面，S2 加子采样+崖面规则；R5"加载过才有"）；
- **帧剔除**：相机 → 距离带 [官方雾起点, 官方 depthFar]（RD32 下 = [448, 2048]）+ cell AABB × 世界 Y 带 + joml `FrustumIntersection`（DH `DhFrustumBounds` 同款姿势）→ 可见集（目标 ≤ 2000 cells）；
- **流式预算**：帧建 ≤ 8 cells、帧驱逐 ≤ 2 cells、驻留上限 8192（LRU 驱逐）；chunk 加载事件 → cell 标 dirty（S1 粗策略：加载即重建）；
- **淡入**：per-cell visibility 淡入时长 ≥ 官方 chunk fade（无 pop-in）；S1 淡入值经 per-cell UBO（`writeChunkSections`）通道下发；
- **合成调试环**（验收 #5 的全带靶子，不计视觉验收）：调试命令（游戏内 key 或 command）生成 [fogStart, depthFar] 全带合成高度场（确定性：正弦叠加 + 基于 cell 坐标的伪随机，可复现），作为合成 cell 集写入注册表；运行时可开关；专门验证流式预算 / VRS / heap 全带机制；

## 5. 帧图接线与关断

- **帧图挂载**：原样复用 S0——`LevelRendererFarPassMixin`（`addWeatherPass` INVOKE @Redirect）+ `FarTerrainRenderer.attach(frame, targets)`（`addPass("far_terrain")` + `targets.main` 读写，共享主目标颜色+深度）；
- **render pass 模板**：S0 模板不变（`createRenderPass(主目标 color+depth)` + `bindDefaultUniforms` + `DynamicTransforms` writeTransform）+ 新增：pass 级 heap bind（§3.2 每帧一次）+ VRS 状态（§2.4）+ 几何 BGL 绑定（§3.3）；
- **关断**：`FarTerrainRenderer.dispose()` 扩展为全量有序幂等释放——合成调试状态 → SSBO arena（raw）→ VBO/IBO arena → 堆内存 → set layout → pipeline（自有 pipeline layout）；`RenderSystemShutdownMixin`（`shutdownRenderer` HEAD，S0 已验证路径，先于 `vkDestroyDevice`）调用；VVL 对象追踪退出零报错（S0 教训：自己的对象必须全部在 `vkDestroyDevice` 前释放）；
- **shutdown 时的 raw 资源**：raw handle 建的 buffer/memory 按 VK 规则 `vkFree...` 释放（设备子对象先于设备，S0 实证）。

## 6. 执行协议（S0 循环不变）

1. 栞问 → 栞启动：`GRADLE_USER_HOME=$PWD/.gradle-user-home JAVA_HOME=$PWD/.gradle-user-home/jdks/... ./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation"`（后台 job + tee 日志），用户在屏幕上盯窗口；
2. 两轮：标准 VVL 档 + 全开档（`VK_LAYER_PATH=$PWD/mc-src/.vanilla-run/vulkan-layers` + `VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile`）；
3. 核验：VVL validation/object-tracking 零消息（对照 `docs/baselines/` vanilla 基线）、diff 干净、截图存档、中招清单 ≤ 5 行；
4. 负门槛 build：独立测试 build（flag 跳过 §2.2 两处 mixin）跑一轮，验证"明确日志禁用 + 干净退出 0"；
5. 证据归档 `docs/s1-acceptance/`（验收记录 + 日志 + 截图），提交。

## 7. 风险与退路

| # | 风险 | 退路 |
|---|---|---|
| R1 | VRS 2026 core 结构体形态与 NV 不同 / 官方创建路径无 pNext 注入点 | 捕获句柄上 raw 自建 pipeline（只 pipeline 走 raw，须完整复制官方 `VkPipelineRenderingCreateInfo` 保证 render pass 兼容，§2.4）；极端情形 VRS 设置缓一步（feature 硬门不缓）——需用户点头后才允许 |
| R2 | 2026 式 VBO 描述符顶点输入语法与 glslang 16.2 的磨合 | 以官方 26.2 shader 语法为模板（同工具链可编译是铁证）；仍不通则 VBO 暂走经典绑定过渡（记录在案、S2 换回堆——ABI 过渡，非设备兜底） |
| R3 | 堆内存 CPU 写入路径语义不明（#3） | `vkWriteResourceDescriptorsEXT` 是 API 正式写入路径；驱动不配合则 host-visible 映射 + CPU 直写（VVL 全程在场验证） |
| R4 | 5090 的 `maxResourceHeapSize` 装不下 8192 cell 全表 | init 实测后降驻留上限（运行时预算），per-cell 子区布局与堆表结构不变 |
| R5 | 官方 encoder 的 raw CBU 取不到（#2） | 探针 mixin（`VulkanCommandEncoder` 字段捕获，S0 战术）或 `@Redirect` 取 commandBuffer 字段 |
| R6 | 探针 mixin 在 S0 真未触发（而非仅缺日志） | 重新布探针：改挂 `VulkanDevice` 构造 HEAD + 字段读取，或 `RenderSystem.getDevice()` 首次调用点捕获 |

## 8. S4 后路合规（R-a~R-f 在本设计中的落点）

| 红线 | 本设计落点 |
|---|---|
| R-a 注册表/堆表是几何唯一权威，渲染器只消费"cell → (几何子区, UBO)"，对来源无感知 | §4 注册表为纯数据结构；§3.3 渲染只读子区偏移+UBO |
| R-b 每 cell block tile（32³ 量化索引）S1 起同步上传 GPU | §3.5 raw SSBO arena，S1 第一天上传 |
| R-c heap 每 cell 子区按 S4 任意 mesh 最坏预算 init 预摊，日后不重建不搬家 | §3.2 子分配策略 |
| R-d mesher 接口 = DAG，S1/S2 = CPU 执行器，S4 只换执行器不换图 | §3.5 DAG 四阶段固定 |
| R-e 顶点格式（position + 基色，无光）不变 | §3.3 顶点格式声明 |
| R-f 任何 ABI 不得 bake"单层天空线"假设 | §4 cell 几何子区 = 任意三角汤预算（高度场只是 S1 的内容，不是 ABI） |
