# S2 修订 / S4 重定义设计规格：CPU 二进制贪心网格化转型（greedy-mesh pivot）

- 日期：2026-09-16
- 状态：设计对话完成；用户全节批准 + 规格审阅通过 + 外部评审回应完毕（2026-09-16，共 7 项裁决，见 §1）→ 下一步 = writing-plans 实施计划（进行中）
- 取代 / 重定义：
  - `docs/superpowers/specs/2026-09-15-s2-lod-ladder-design.md`（S2 规格 v1）：其 §3（LOD 阶梯）
    数据模型与本规格 §4/§5 冲突处以本规格为准；其步骤1（秒表，已闭合）/步骤3（遮挡剔除）/
    步骤4（VRS rate map）/步骤5（远平面三件套）的机制细节继续有效，为 v2 步骤 5/6/7 的验收基线；
  - `docs/superpowers/plans/2026-09-15-phase2-dh-geometry-hauler.md` §4 S4 章：由本规格 §10 重定义。
- 依据：
  - 用户下载的汇总文档《dhvk-远景几何与LOD技术建议汇总》（2026-09-16；基于对本仓库实际代码
    tarball 全量读取 + Vulkan Roadmap 2026 官方公告（Khronos 2026-01-23）的核实；原文存于用户
    下载目录，本规格摘录其 §1/§3/§4/§5/§6/§8 要点，原文明确"不是新的裁决文档"）；
  - 用户四次裁决（原话，见 §1）；
  - S1 闭合 @`4bd9acc`、S2 步骤1（GPU 秒表）闭合 @`68278e1`（pivot 落在全部闸门已过的地基上；
    用户表态："目前全部是测试 descriptor heap，几何/LOD 代码还没真正开始写，是转型成本最低的窗口"）；
  - API 基线：refs/vulkan-headers 1.4.357 + 游戏随包 LWJGL 3.4.1。

## 0. 定位一句话（pivot 摘要）

远景几何生产者从"列高度场（per-column 顶面）"整体切换到 **CPU 二进制贪心网格化 + 官方 terrain
atlas 真贴图 + 烘焙 AO**；全部 LOD 级共享同一数据模型（每级 = 一个 32³ 体素网格 → 跑同一套
贪心算法）；高度场路径退役（目验基线 = run45-50 归档截图）。**"真体素正主"从 S4 提前到修订版
S2 兑现**（Voxy 式真远景：崖壁/悬挑/洞口/树在远处可读，从"秃山"变"糊色块"而非"消失"）；
S4 重定义为"留门（CellMeshExtractor 第三实现位）+ 出货口（CellGeometryObserver → Caustica RTAS）"。

**继承（S1 闭合资产，不动）**：设备手术 + 硬门槛（不兜底）/ heap 载体（每帧一次整表 bind）/
raw SSBO block tile（R-b）/ VRS 设备手术 / 探针三件套 / dispose 纪律 / GPU 秒表（已闭合）/
合成调试环（墙 A/B + 探针板，VoxelWallSynthesizer + DHVK_NOWALL/NOPROBE，降级为调试工具，
**与高度场退役无关**——退役的是 cell 高度场提取路径，调试环的墙几何照旧）。

## 1. 裁决记录（用户四次裁决，原话引用）

1. **S4 范围**："C：B + caustica RTAS 出货口" → pivot 后明确为：S4 = 留门（第三实现位，只冻
   seam 不写码）+ 出货口（接口登记，不研究 BLAS）；GPU compute mesher 本体降为远期可选升级
   （同一接口的第三种实现）；
2. **高度场的下场**："B：高度场退役" → 阶梯全层级走体素贪心，S1 高度场提取代码于 spike 过闸后
   退役（目验基线改用归档截图）；唯一保留退路 = spike"形状"失败时重开 pivot 讨论（B 裁定可被
   目验推翻）；
3. **贴图/AO 归属**："A：贴图/AO 焊进 S2" → 10km 总闸门以"真贴图 + AO"验收；精度带/远景带
   交界 A/B 闸只有在精度带真贴图前提下才成立；
4. **搬运工原则（全节批准时追加）**："可以的但是注意就是少碰不碰原版渲染，我们只做几何搬运工"
   → 落地为 §3 触碰面台账：**pivot 增量对官方渲染路径零新增触碰点**。

5. **总基调（规格批准时收官裁决）**："我觉得挺好的，总之克制，留出未来几乎全gpu的办法，就可以" → 落地 = 本规格 YAGNI 底线（S4 两接口只冻 seam 不写码、不做 device-driven 剔除、不研究 BLAS、不自建文件格式）+ 未来"几乎全 GPU"通道全保留（R-d DAG 执行器可换、§5.1 SoA 布局"GPU 端也能直接写"、CellMeshExtractor 第三实现位 = GPU compute / shader enqueue、CellGeometryObserver → RTAS）。
6. **缺数据规则（2026-09-16 外部评审补入，用户裁定 = A）**："90% 可建阈值 + 缺样本当空气"——L1 cell 须辖下 ≥90% 的 64 个 L0 tile 有数据才可建（L2/L3 继承 = ≥90% 子级 cell 已建）；不达标的 cell 整个不建；可建 cell 内缺样本当"空气"参选（64 票含缺席票，投票规则不变）；驱逐滞后带内数据（R5 最后数据）不算缺失。全文见 §4.2。
7. **外部评审回应记录（2026-09-16）**：BVH/RTAS 进 heap 论断经 1.4.357 头文件 + 3.4.1 绑定三锚点核验（§10② 备注已记，范围不扩）；L1+ 派生隐藏成本 = 成本分档 + 分帧渐进 + 触发规则 + 驻留缓存退路（全文见 §4.2 / §8 任务2 闸门）；评审其余结论（触碰面台账 / UV 平铺 / spike 失败裁决 / 风险表）已在规格正文内。

## 2. 认知更正：Voxy 真实架构

旧总计划 S4 章"Voxy 式 **GPU** 体素 mesher"是对 Voxy 实际架构的误读（汇总文档核实）：

- Voxy 的网格生成在 **CPU 多线程后台**跑（"Service Threads"配置项、与 Sodium chunk 更新线程
  同步），算法 = 经典**二进制贪心网格化**（occupancy bitmask → 位运算面剔除 → 贪心合并四边形），
  参考 cgerikj/binary-greedy-meshing（64³ chunk 单线程 ~74µs）；
- Voxy 的"GPU 驱动"体现在**渲染侧**：OpenGL DSA 高效上传 + indirect multi-draw
  （`glMultiDrawElementsIndirectCountARB`；Vulkan 对应 `vkCmdDrawIndexedIndirectCount`）——
  此项本项目的 `VK_EXT_descriptor_heap` 是更直接的答案（per-region 绑定 = 堆子分配，
  写描述符 = 写内存），S1 已落地；
- Voxy 的"精度"真正来自：真实方块面几何 + 贪心合并（不是列高度场）、真贴图 + biome 染色、
  部分高度方块（台阶/雪层）、树叶冠层特殊处理、半透明单独通道。

**结论**：GPU compute mesher 降级为远期可选升级项（CellMeshExtractor 第三实现）；
"CPU 贪心网格 + 真材质 + heap"于修订版 S2 兑现——这才是"类 Voxy"的正确形态。

## 3. 搬运工原则与官方触碰面台账（裁决 4 落地）

**原则**：pivot 增量对官方代码**零新增 @Inject/@Redirect**；一切新增 = 只读消费官方资产
（terrain atlas GpuTexture 句柄 / 方块染色与精灵查询 / atlas 精灵布局），新描述符
（terrain atlas sampler）挂**我方**管线 BGL，指向官方纹理对象（读官方对象取句柄，不钩官方代码）。

**触碰面台账**（mod 对官方代码的全部触碰点，一表到底；此后任何新增须先在本表登记并走裁决）：

| # | 触碰点 | 阶段 | 性质 |
|---|---|---|---|
| 1 | VkHandlesProbeMixin（VulkanDevice init） | S0 | 句柄捕获（读） |
| 2 | VulkanBackendDeviceSurgeryMixin（设备创建） | S1 | feature 手术（写，init 一次性） |
| 3 | VulkanConstBdaUsageMixin / VulkanTransientMemorySurgeryMixin / VulkanRenderPipelineSurgeryMixin / VulkanRenderPassPushCancelMixin / CommandEncoderDhvkProbeMixin / VulkanCommandEncoderProbeMixin / RenderPassUniformProbeMixin | S1 | 探针 / 最小手术（明细见 S1 规格） |
| 4 | LevelRendererFarPassMixin（我方 pass 帧钩） | S0/S1 | 帧同步点（钩，不改官方逻辑） |
| 5 | RenderSystemShutdownMixin（dispose） | S0 | 释放时序 |
| 6 | ClientPackSourceDevResourcesMixin（namespace） | S0 | 资源可见性 |
| 7 | CameraRenderState 投影 far / FogRenderer.updateBuffer（远平面三件套） | S2 步骤7（v1 规格，已裁） | 单点覆写（v1 已裁决） |
| 8 | **（pivot 增量，预期 = 零行新增）**：terrain atlas sampler 入我方 BGL；sprite 矩形表 / biome 染色 = 只读消费官方数据 | S2 修订 | **无新官方代码触碰点** |

**退路**（读码判姿势不合时）：官方 atlas sampler 挂在不共享的 BGL 组 → 我方自建 sampler 组
（probe 句柄在手，S1 管线自建先例）——同样零官方代码触碰点。若连这也行不通（预期不会），
开新读码先决项裁决，不许绕过台账。

## 4. 数据模型与 LOD 阶梯（S2 v1 规格 §3.1/§3.2 改写）

### 4.1 L0 tile（红线 R-b，不变且加强）

- 32³ block tile（调色板索引量化紧凑格式，~3~8KB/cell）S1 起同步上传 GPU；
- **CPU 提取器读与 GPU 上传同源的 tile 镜像副本**——不开"CPU 直接读 chunk 内存"的孤立路径
  → tile 格式设计提前受验证（汇总文档 §4.1）；
- tile 携带**邻 cell 1 层 margin**（面剔除需读邻 cell 边缘带做面剔除；S1 33×33 网格是否已含
  margin 列 = 任务 0' 与"33×33 margin"读码先决项合并成一次勘察裁决）。

### 4.2 每级同构网格与级联投票（本 pivot 的核心数据模型）

- **每级网格输入 = 一个 32³ 体素网格**：occupancy（实心）+ 逐体素 spriteID（u16）+ tint（rgb8）；
- **L1 网格 = 跨 tile 投票**：L1 网格顶点 (x,y,z)（覆盖 [4x,4x+4)³ blocks）= 64 样本多数，
  样本 = 该 L1 cell 下 64 个 L0 tile 各自在偏移 ((4x)%32, (4y)%32, (4z)%32) 处的体素
  （每 tile 恰贡献 1 样本，64 次查表 + 多数，µs 级）；
- **L2/L3 网格 = 单级网格上的一次跨格投票**：L(k+1) 网格顶点 = 对**单个** L(k) 网格的
  4×4×4 共 64 样本多数（偏移 (4x)%32, (4y)%32, (4z)%32）；L(k) 网格**派生即用、不驻留**
  （驻留的只有 L0 tile，§4.1）；L2 构建 = 64 个 L1 网格派生 × 各 64 查表 ≈ 一次性 10⁸ 次
  查表（ms 级，进帧一次性构建预算，实测数回填）；L3 同理；
- **投票规则（裁定的）**：occupancy 多数 = 实心（≥半；平手 → 实心，保守防洞）；
  spriteID / tint 与 occupancy 同次多数投票（平手 → 最小索引 / 首位，确定性可复现）；
- **缺数据规则（2026-09-16 裁定，§1-6）**："L0 tile 无数据" = 从未产生（驱逐滞后带内数据 = R5"最后一次数据"，不算缺失）：
  ① cell 级可建性：L1 cell 须辖下 ≥90% 的 64 个 L0 tile 有数据才可建（初值，目验可调 75/100，调参 = 参数 + 日志记录，不改代码）；
  L2/L3 继承 = ≥90% 该级子 cell 已建；不达标 cell 整个不建（不画、不驻留）——剪影只出现在有数据处，
  "去过 / 没去过"的数据边界溶进雾里（与任务7 的 fogStart 交界带裁定协同）；
  ② 样本级：可建 cell 内缺样本当"空气"参选（64 票含缺席票），投票规则不变（多数；平手 → 实心防洞）；
- **重建触发规则（2026-09-16 裁定）**：重提取 ⟺ tile generation 变化；重派生 ⟺ 子级网格变化；
  带内进出（抖动）只改 draw 列表 + LRU 驻留态（只重上传，不重提取不重派生）——"抖动反复重建"最坏情况由此规则封闭；
- **首次派生成本分档与分帧渐进（2026-09-16 裁定，任务2 闸门硬指标）**：L1 网格 ≤5ms / L2 网格 ≤50ms
  （一次性，5090 初值）；**L3 网格 = 分帧渐进任务**（每帧成本 ≤2ms CPU 初值，自动调节闭环接管），
  cell 在级联完成后才进 draw 列表（之前由低级别几何覆盖该区域——"远景渐进得到细节"即填充的可见形态，
  填充时间以秒计落日志供用户裁决）；
- **退路（2026-09-16 裁定）**：L3 分帧填充若被目验判"远景细节来得太慢" → L1/L2 网格升**驻留缓存**
  （LRU，随 cell 驱逐滞后带；内存 = 驻留 cell 数 × 每网格尺寸，实测入档）→ 单独一道裁决；此前维持"派生即用"。
- **每级几何 = 对每级 32³ 网格跑同一套贪心算法**（§6）→ 视觉语言全层级统一，消除
  "两套数据模型硬拼接"的接缝（汇总文档 §6 的核心理由）；LOD 仅 cell 边长 ×4/级，
  同一 shader，无变体（继承 S2 v1 规格裁决）；
- **档位表 / 带宽表 / ±20% 滞后带防抖 / 帧建-驱逐预算 / 秒表驱动自动调节闭环**：
  全部继承 S2 v1 规格 §3.1/§3.2 不变（级别 32/128/512/2048）；预算初值在 spike / 阶梯闸门
  实测后回填自动调节控制器（不手调，§9）；
- 驻留内存：GPU 驻留 = 堆子区（LRU，上限 8192 cell）；CPU 驻留 = L0 tile（R5 滞后带内保留
  "最后一次数据"）；L1+ 网格瞬态（派生后弃）；
- 精度约定（继承 S2 v1 规格 §3.3 不变）：顶点 = cell 原点相对坐标（±S/2 内 float32 无损）+
  cell 原点经 ModelViewMat 平移；≥32km 切官方 ModelOffset（S3）。

## 5. 红线 v2（正式裁决，取代总计划 §4 的 R-a~R-f）

| 红线 | v1 | v2（本规格裁决） |
|---|---|---|
| R-a | 注册表/堆表是几何唯一权威，渲染器只消费"cell → (几何子区, UBO)" | **不变** |
| R-b | 32³ block tile 从 S1 起同步上传 GPU | **不变且加强**：CPU 提取与 GPU 上传共享同一次 tile 构建；tile 带 1 层邻 margin（§4.1） |
| R-c | 堆子区按 S4 最坏预算 init 预摊，不重建不搬家 | **改判**：预算 = spike 实测真实 chunk（平地/山地/森林/洞穴口四类）贪心最坏顶点/索引数 **× 1.5 安全系数**，init 前定死；"超预算只报警不兜底"原则保留；若实测超 S1 预摊子区尺寸 → 放大堆 init 常数（在阶梯代码落地前完成，仍处最低成本窗口，堆表 init 重建、非运行期搬家） |
| R-d | mesher 接口 = DAG（ready→提取→上传→表更新），执行器可换 | **形状不变，接口定死**：`CellMeshExtractor.extract(CellKey, BlockTileView, HeapSubregion)`；v2 实现 = `CpuGreedyMeshExtractor`；第三实现位预留 S4（GPU compute / shader enqueue，只冻 seam）；`HeapSubregion` 字节布局按 §5.1 SoA，"GPU 端也能直接写"（不用 Java 对象/List 中间表示） |
| R-e | 顶点 = position+基色，无光，不变 | **正式修订** = §5.1 SoA 布局（ABI 第一天焊死，采样分阶段启用：spike 走纯色分支） |
| R-f | 任何 ABI 不得 bake"单层天空线"假设 | **不变**（每级贪心 = 多层点列的超集；任意三角汤走同一张表） |

### 5.1 R-e v2 顶点布局（SoA，GPU-writable）

| 属性 | 类型 | B/顶点 | 对齐 | 说明 |
|---|---|---|---|---|
| pos | f32×3 | 12 | 4 | cell 原点相对坐标 |
| uvAbs | f32×2 | 8 | 4 | 面局部 UV 轴上的**未展开**世界方块坐标（轴向由出面方向在提取时定死，shader 不做轴变换） |
| spriteID | u16 | 2 | 2 | per-pass 精灵矩形表索引（表大小 = 在用精灵数，任务 0' 实测定；u16 容量核对见风险 N5） |
| ao | u8 | 1 | 1 | 烘焙 AO 0..3（逐顶点 3 邻角实心数 → 经典 MC AO 档，纯几何邻查询） |
| tint | rgb8 | 3 | 1 | 官方 biome 染色（提取时烘焙；26.2 反混淆染色 API = 读码先决） |
| normal | u8 | 1 | 1 | 3bit 面方向（无光，留未来光照 / RTAS） |

SoA = 每属性独立堆内区域、逐属性自然对齐；单顶点合计 27B。

### 5.2 UV 平铺规则（写码前最后定稿，首跑目验终裁）

- 问题（汇总文档 §4.5）：贪心合并出的大四边形若把 UV 从 0 拉伸到 1，贴图被拉花；
- 规则：mesher 把面轴世界坐标（未展开，如 103.4）烘进 `uvAbs`；shader 侧
  `rect.xy + mod(uvAbs, 1.0) × rect.zw`（rect = 精灵 atlas UV 矩形，含 padding）完成按方块
  平铺——线性插值的未展开量 + shader 端 mod = 跨方块接缝处正确平铺，不拉花；
- 公式定稿 = 任务 0' 读码输出：官方 atlas 的 padding / y 翻转 / 精灵矩形来源，以及**官方
  Vulkan chunk 管线处理 atlas UV / 精灵选择的姿势**（搬运工精神：镜像官方，不自创）；
- spriteID → rect 表 = CPU 从官方 atlas 布局（只读消费）构建，per-pass 上传小 uniform / SSBO 表；
- 验收手段 = 贴图闸的放大 A/B 目验（拉花 / 轴错 / bleed 一眼可见）。

### 5.3 shader 与渲染接线（我方 pass，零官方触碰点）

- `far_terrain.vsh`：新增 `in uvAbs / spriteID / ao / tint / normal` 顶点属性（顶点输入布局 =
  §5.1；S1 的堆源幻象绑定 / 判别式机制不动）；
- `far_terrain.fsh`：新增官方 terrain atlas sampler + `texture()` 采样，
  `texc = sample × tint`，AO 按烘焙档压暗（压暗曲线在 AO 闸目验调），官方 apply_fog 双通道
  距离雾不动；
- **`DHVK_TEX` 因子开关**：0 = S1 纯色脸（vertexColor 路径，spike 阶段走此）/ 1 = atlas 采样
  + AO + tint——贴图闸 / AO 闸分阶段验收，同屏可切 A/B；
- BGL：我方 pass 新增一组 sampler 描述符集（指向官方 terrain atlas GpuTexture，句柄只读取自
  官方对象）——绑定路径镜像官方姿势（读码先决 §11-2），退路 = 自建 sampler 组（§3 台账 #8 备注）。

## 6. 贪心 mesher 本体

- 算法 = 二进制贪心网格化（参照仓库 `cgerikj/binary-greedy-meshing`，clone 进 `refs/` 读码
  参照；**git clone 执行前按规矩向用户点头**）：occupancy bitmask 面剔除（位运算）→
  八叉树贪心合并四边形；
- MC 化改造（原算法体素类型有限塞一个 word；MC 方块种类多，汇总文档 §4.2 改法）：
  1. bitmask 只记"实心"（面剔除用）；贴图 / 染色另开数组按坐标查；
  2. **贪心合并只允许同 sprite + 同 tint 的相邻面**（不同精灵即使共面也不合并）；
  3. "实心" = 非 air 且可见（opaque + cutout 如树叶；半透明单独通道不背——远景不画水 / 玻璃，
     水 = 官方世界，非目标）；
  4. **六方向全部出面**（含侧面 / 底面）→ 崖壁 / 悬挑 / 洞口天然可读（高度场做不到的两条，
     汇总文档 §6 表）；
- 烘焙 AO = 逐顶点 3 邻角实心数 → 0..3（经典 MC 环境光遮蔽，纯几何邻查询，不碰游戏光照系统、
  不与光影包打架，汇总文档 §4.3"性价比最高"项）；
- CPU 成本：32³ 贪心 µs~数十 µs 级（参照仓库 64³ = ~74µs 单线程）→ spike 实测数回填帧预算
  （S2 v1 规格 §3.2 帧建预算初值按实测调整，自动调节闭环接管，§9）；
- 提取流遵守 R-d DAG：[block 数据 ready] → [表面提取（贪心）] → [几何上传] → [表更新]；
  S1/S2 执行器 = CPU（CpuGreedyMeshExtractor），S4 执行器 = GPU compute / shader enqueue
  （同一张图，换引擎不换图）。

## 7. 贪心 spike（修订版 S2 第一道闸，汇总文档 §4.8）

- 场景：一个真实**混合 chunk**（含树 / 悬崖 / 洞口；用户游戏内选点并归档坐标）；
- 路径：tile 构建（带 margin）→ 贪心合并 → 堆子区（R-c 预算此时为暂定值，实测后定死）→
  **纯色渲染（DHVK_TEX=0：S1 那张脸 + 新形状）** → 秒表（mesher CPU µs / far pass GPU µs /
  三角形数 / 顶点数）→ 目验；
- 通过标准（四项全过）：
  1. **形状三项目验**：崖壁可读（不是平滑坡）、树 = 色块（而非消失变秃山）、洞口可见；
  2. far pass 1ms 预算内（秒表已就位，S2 步骤1 资产）；
  3. VVL 标准档 + 全开档双零；
  4. 实测最坏顶点 / 索引数（平地 / 山地 / 森林 / 洞穴口四类真实 chunk 逐一补测）→ **R-c 预算
     数字定死入档**；若超 S1 预摊子区尺寸 → 按 §5 R-c 行放大堆 init 常数；
- 失败裁决：**形状失败（第 1 项任一条）= 重开 pivot 讨论**（高度场退役裁定可被目验推翻，
  唯一保留退路）；"纯色显平" = 预期内，由贴图闸解决，不算失败；
- 过闸后：① 高度场提取代码退役（独立 commit"高度场退役"；目验基线 = run45-50 归档截图）；
  ② **R-e / R-c 正式裁决记录**（本规格 §5 即裁决记录，回填裁决日期）；
  ③ 验收归档 `docs/s2-acceptance/` + commit（含 ≤5 行中招清单）。

## 8. 修订版 S2 步骤序与验收闸门（每闸独立，过一闸进一闸，哪件都不做兜底）

| 步骤 | 内容 | 闸门项（全部硬门） |
|---|---|---|
| 0' | 读码先决补账（§11 任务0' 六项）+ 两个接口壳（`CellMeshExtractor` / `CellGeometryObserver`，几十行，汇总文档 §5"现在就把门留下"）+ 参照仓库 clone（确认制） | 读码笔记回填 `docs/s2-code-reading-notes.md`；接口编译 + lint 过 |
| 1 | **贪心 spike**（§7）→ R-e / R-c 裁决 | §7 四项 |
| 2 | **LOD 阶梯（体素版）**：级联投票（§4.2）+ 逐级贪心 + 档位带宽表（带宽不变）+ per-cell UBO 并入堆（S2 v1 §3.3 原样：CHUNK_SECTION 成堆内描述符、官方动态通道退役为 push 壳、数据源判别探针） | 价目表出现 L0~L3 分行且数值合理；L0/L1 交界无 pop-in（近带切片 + 合成环目验）；VVL 双零；+1ms（RD32 / 5090）；UBO 数据源 = 堆（探针判别式）；L1/L2/L3 网格首次派生成本实测落日志（L1 ≤5ms / L2 ≤50ms / L3 分帧 ≤2ms·帧，填充时间以秒计落日志）；重提取仅发生在 tile generation 变化（抽查：带内进出无重提取）；填充过慢 → L1/L2 网格升驻留缓存（独立裁决，§4.2 退路） |
| 3 | **贴图 / UV**：atlas 采样 + 平铺规则（§5.2）+ tint（DHVK_TEX=1） | 放大 A/B 目验（拉花 / 轴错 / bleed 一眼可见）；VVL 双零；+1ms 预算 |
| 4 | **烘焙 AO**（AO 压暗生效） | 立体感目验（崖壁有深浅、树块有体积）；VVL 双零；+1ms 预算 |
| 5 | **遮挡剔除**（S2 v1 §4 原样：构建期点剔除 + 帧级区域剔除，均 CPU 侧——对任意几何同样成立；点剔除规则 = "邻列有更高点则剔"，**由高度场规则适配为"同级邻 tile 有更高实心"**，具体读 DH FullDataOcclusionCuller 源定稿；帧级视线路径查询由"列顶高度场"改为"L0 tile 预计算的逐列顶面表"（§12 N6）） | 秒表 A/B（山地合成场景 DHVK_MOUNTAIN：far pass µs + drawn cell 数前后对比）；山地剪影无破洞（用户目验 + 确定性山地场景）；帧级剔除 CPU ≤0.2ms；VVL 双零 |
| 6 | **VRS rate map**（S2 v1 §5 原样：rate image 取代固定 2×2；任务 0 顺带读汇总文档 §1 的 Host Image Copies——rate map 上传或可省一层中转） | rate image 取代固定 2×2 且 VVL 双零；秒表 delta 三组数（rate map vs 固定 2×2 vs 全 1×1）落日志；用户 A/B 目验（4×4 / 8×8 粗糙度终裁）；8×8 若 5090 不暴露则封顶 4×4（运行时预算，非设备兜底） |
| 7 | **远平面三件套 = 10km 总闸门**（S2 v1 §6 原样：远平面 hook + fog end 随 bandFar 扩展 + ndcz 重推导） | 10km 带（L0~L3 全阶梯，**带真贴图 + AO**）填满地平线（用户目验）；5090 +2ms/帧（秒表）；VVL 双零；价目表 L0~L3 分行合理；与 vanilla 基线 diff 干净（帧提交 / 队列不变）；深度精度抽查（10km cell 边缘无抖动 / 无 z-fight，探针墙 + 目验）；ndcz 参数化公式写入 `docs/mc26.2-vk-reference.md`；**交界带 A/B 截图闸：fogStart 卡在刚过精度带边界一点点，边界前后一圈 A/B 截图（让"变假"发生在雾里）** |

**负门槛**（S1 先例继承）：硬门槛自禁用时，阶梯 / 剔除 / VRS / 秒表 / 远平面三件套全部随之
不启用（无独立启用路径），游戏照常干净运行。

**S3 随之瘦身**（S2/S3 边界 A 裁定不变）：距离 alpha ramp（"贴图 → 雾色"渐变）、≥32km
ModelOffset、地曲率视觉项（可选）、10/20km 截图打磨。

## 9. 遥测与控制闭环继承（S2 v1 规格 §2/§3.2，不变）

GPU 秒表（v1 步骤1 已闭合 @`68278e1`）：官方 timeline semaphore 搭车 + 官方公开
`writeTimestamp` / `createTimestampQueryPool`；per-LOD 一对槽位已预摊（query pool N=10，
表不重建）。自动调节闭环（far pass 超预算 → 自最远档起逐级帧建预算 ×0.8（有 floor）→
富余时 ×1.1 回补（有 cap）→ 步骤7 起 bandFar 可收缩 ×0.9）**接管贪心帧预算**——本规格
所有预算初值都是"暂定值，spike / 阶梯闸门实测回填控制器"，不手调（汇总文档 §7：
"给精度定可测试的及格线，而不是尽量高"）。

## 10. S3 / S4 重定义

- **S3**（打磨）：范围不变（alpha ramp / ModelOffset / 地曲率可选 / 截图打磨），仅视觉基线
  由"纯色剪影"变为"真贴图 + AO"（ramp = 贴图 → 雾色渐溶）；
- **S4 = 留门 + 出货口**（总计划 §4 S4 章重定义，原纲要保留作历史）：
  - ① **CellMeshExtractor 第三实现位**：GPU compute mesher（2026 里程碑 compute shader
    derivatives dFdx/dFdy 崖面 / 边缘检测替代 CPU 启发式）/ 未来设备侧 working group /
    shader enqueue 提案（尚未入里程碑）——**只冻 seam 不写码**（与总计划 §4a"仅预留，
    不写码"记法一致）；接口输出契约（§5.1 SoA）第一天就按"GPU 端也能直接写"设计；
  - ② **Caustica RTAS 出货口**（经 `CellGeometryObserver`）：任务 0' 登记接口，不写实现、
    不研究 `vkBuildAccelerationStructureKHR` / BLAS（压缩策略 / 每帧 refit 预算 / 内存管理
    = 实打实的工作量，属"没有需求先做"的过度设计）；届时：注册表 diff → BLAS / TLAS，
    硬件光追视角与官方光栅远景共用同一份几何。**备注（2026-09-16 评审核实）**：VK_EXT_descriptor_heap 的 resource heap 可含加速结构描述符——VkDescriptorType 已含 VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR = 1000150000（refs/vulkan-headers 1.4.357 vulkan_core.h:2411）、写路径 = 与现有 image/buffer 描述符同一个 vkWriteResourceDescriptorsEXT（vulkan_core.h:16986）、游戏随包 LWJGL 3.4.1 绑定含 VK_SPIRV_RESOURCE_TYPE_ACCELERATION_STRUCTURE_BIT_EXT 能力位；届时绑定层零新增机制——真正的成本在 BLAS/TLAS 构建 / 压缩 / refit / 内存管理（正是"不研究"的范围），此备注不扩大现行工作范围。
  - GPU mesher 的"加载范围之外"超远数据（DH mimic 世界生成器 + 磁盘 full-data 缓存路线）
    = S4+ 可选项，不在本规格范围。

## 11. 读码先决清单（按步销账，全部回填 `docs/s2-code-reading-notes.md`）

- **任务 0'（spike 前）**：
  1. S2 v1 规格 §10 遗留 4 项：剔除算术落数 / 33×33 margin 核实 / visibility 双通道写路径 /
     5090 `maxResourceHeapSize` vs 全表（R-c v2 预算后的 L0~L3 尺寸）；
  2. **官方 terrain atlas 绑定路径**：官方 Vulkan chunk 管线如何把 atlas 绑进管线
     （BGL / sampler 组 / 句柄获取）→ 我方 pass 镜像同姿势（§3 / §5.3）；官方 atlas UV
     约定（padding / y 翻转 / 精灵矩形来源，§5.2 公式定稿输入）；
  3. 26.2 反混淆 **biome 染色 / 精灵查询 API**（官方 BakedModel / BlockColors 等价面：
     提取时如何拿 per-block spriteID + tint）；
  4. tile margin 实现（§4.1 的 1 层邻 margin 与 33×33 网格 margin 列合并勘察）；
  5. 精灵矩形表规模 / 上传路径（小 uniform 还是 SSBO、per-frame 还是变更时；u16 容量核对）；
  6. clone `cgerikj/binary-greedy-meshing` 进 `refs/`（确认制）+ 读算法核心
     （bitmask 布局 / 八叉树合并 / 索引输出格式 → 映射到我方 HeapSubregion SoA 布局）。
- **步骤2**：L1+ 网格派生成本实测（成本分档 + L3 分帧填充时间，§4.2 硬指标）；缺数据规则参数（90% 初值，目验可调）；重提取触发规则抽查（generation 变化才重提取，带内进出只重上传）；visibility 双通道（S2 v1 §3.3）。
- **步骤5**：DH `FullDataOcclusionCuller` 具体规则读源（refs/dh）并适配体素形（§8 步骤5 备注）；
  L0 tile 逐列顶面表的预计算 / 查询接口（帧级视线路径 O(1) 查表，§12 N6）；山地合成场景
  （确定性、`DHVK_MOUNTAIN` 开关）。
- **步骤6**：官方 createRenderPass → VkRenderingInfo 构建点（有无 pNext 注入，S2 v1 §5.2 原样）；
  rate image 规范文本（format / layout / usage / 组合语义）；5090 FSR properties dump；
  rate image × dynamic rendering × heap 绑定共存 VVL 首验；**Host Image Copies**
  （汇总文档 §1：rate map 上传或可省 `vkCmdCopyBufferToImage` 中转层）。
- **步骤7**：`CameraRenderState.projectionMatrix` 构建点（候选①/②，S2 v1 §6.1 原样）；
  `FogRenderer.updateBuffer` 单点确认 + Fog 块偏移复核；ndcz 参数化公式；bandFar·k 系数首跑标定。

## 12. 风险与退路

| # | 步骤 | 风险 | 退路 |
|---|---|---|---|
| N1 | 0'/1 | 贪心最坏量（棋盘 / 密林）超 R-c 预算 | 1.5 安全系数 + 只报警；极端时逐档三角帽（深洞底面弃出等）目验裁决；超 S1 预摊尺寸 → 放大堆 init 常数（§5 R-c 行） |
| N2 | 0'/3 | 官方 atlas 绑定姿势与我方管线不合 | 自建 sampler 组（probe 句柄在手，S1 管线自建先例；零官方代码触碰点，§3 台账 #8） |
| N3 | 3 | UV 平铺公式错（拉花 / 轴错 / bleed） | 放大 A/B 闸目验终裁，公式修正一行级（定稿是任务 0' 读码输出，§5.2） |
| N4 | 2 | 级联投票平手规则致"蜂窝感" | 投票规则参数（平手实心 / 平手空气）可调，目验闸终裁（§4.2 裁定初值 = 平手实心防洞） |
| N5 | 0'/1 | spriteID 超 u16 表容量（精灵爆炸） | 任务 0' 实测在用精灵数；必要时升 u32（SoA 布局 stride 变更，ABI 定稿前完成） |
| N6 | 5 | 帧级剔除视线路径（原查列顶高度场）需适配 occupancy | L0 tile 构建时预计算逐列顶面表（一次性成本），查询仍 O(1) 查表，0.2ms 目标不变 |
| N7 | 0'/2 | "去过 / 没去过"数据边界处剪影硬边（缺数据缺口，§4.2） | 缺数据规则（90% 可建阈值 + 缺样本当空气）+ 任务7 fogStart 交界带（"变假发生在雾里"）；阈值过严 / 过松 → 参数调（目验，初值 90%） |
| N8 | 2 | L3 分帧填充太慢（"远景细节 N 秒才来"目验不可接受） | L1/L2 网格驻留缓存（LRU + cell 滞后带；内存实测入档）；独立一道裁决（§4.2 退路） |
| — | — | S2 v1 规格 R1~R11（timeline 读回 / timestamp stage / 父建滞后 / 子区预算 / 误剔 / CPU 超预算 / VkRenderingInfo 注入 / rate image 合规 / 8×8 暴露 / cameraState / Fog 偏移） | **全部继续适用**（秒表 / rate map / 远平面机制未动，明细见 S2 v1 规格 §7） |
| （可选） | — | 单块 5090 VVL 双零 ≠ 其他驱动稳（步骤1 已踩"5090 自报 timestamp 参数不可信"厂商坑） | 安排一次 AMD / Intel 卡标准档 VVL 交叉验证（非闸门，越早越好，汇总文档 §7） |

## 13. 执行协议（与 S1/S2 一致，按步切片）

1. 栞问 → 栞启动：S1 同款命令块（`:mod:runClient --args="--graphicsBackend vulkan
   --vulkanValidation"`，后台 job + tee，日志标签 `s2v2-run-<步骤>-<HHMMSS>`）；
2. 每步：（有先决则先勘察 → s2 笔记回填）→ 代码 → `:mod:build` + lint → VVL 标准档 +
   全开档两轮 → 用户目验裁决 → 证据归档 `docs/s2-acceptance/` + commit（含 ≤5 行中招清单）；
3. 每步验收结果回填总计划 §6 与文档（ndcz 公式 → mc26.2-vk-reference.md）；
4. 代码读码笔记契约同 S1：`docs/s2-code-reading-notes.md` 是后续任务事实来源，
   与代码冲突时以笔记为准；触碰面台账（§3）与代码冲突时以台账为准并修笔记。

## 14. 文档关系与下一步

- 本规格 = S2 规格 v2 裁决（LOD 阶梯数据模型 / 红线 v2 / 步骤序由本规格改写；
  秒表 / 遮挡剔除 / VRS rate map / 远平面三件套机制细节仍以 S2 v1 规格为基线）；
- 总计划 `docs/superpowers/plans/2026-09-15-phase2-dh-geometry-hauler.md`：S4 章按 §10
  重定义（原纲要保留作历史，R-a~R-f 表由本规格 §5 v2 取代），§6 当前状态回填 pivot 落点；
- S2 v1 规格 `docs/superpowers/specs/2026-09-15-s2-lod-ladder-design.md`：头部加 v2 指针，
  原文保留为历史与机制基线——步骤号对照：v1 步骤1（秒表）已闭合、继承为遥测基线（不再重编号）；
  v1 步骤2（阶梯）→ v2 步骤2（体素版改写，本规格 §4/§6）；v1 步骤3（剔除）→ v2 步骤5；
  v1 步骤4（VRS rate map）→ v2 步骤6；v1 步骤5（远平面三件套）→ v2 步骤7；
- **下一步**：用户审阅本规格 ✅ 通过（2026-09-16）→ writing-plans 实施计划 = docs/superpowers/plans/2026-09-16-s2v2-greedy-mesh-pivot.md（任务切片 = §8 步骤序；执行方式由用户在两选项中选定）。
