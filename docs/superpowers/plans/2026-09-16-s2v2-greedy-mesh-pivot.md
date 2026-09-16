# S2 修订（greedy-mesh pivot）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 远景几何生产者切换为 CPU 二进制贪心网格化 + 官方 terrain atlas 真贴图 + 烘焙 AO，依次过七闸（任务0' 侦察 → 贪心 spike → 体素阶梯 → 贴图/UV → 烘焙 AO → 遮挡剔除 → VRS rate map → 远平面三件套 10km 总闸），高度场路径退役。

**Architecture:** 几何权威 = CellRegistry + descriptor heap 表（S1 继承不动）；生产者换 `CpuGreedyMeshExtractor`（实现 `CellMeshExtractor`，R-d），读与 GPU 上传同源的 32³ block tile（R-b）；每 LOD 级 = 级联多数投票派生 32³ 同构网格（派生即用，L3 分帧渐进）→ 同一套贪心算法（R-f）；渲染走既有 `FarTerrainRenderer` pass（零新官方触碰点，规格 §3 台账）；遮挡剔除 / VRS rate map / 远平面三件套机制以 S2 v1 规格 §4/§5/§6 为基线。

**Tech Stack:** Java 25 (Temurin) + fabric-loom 1.17.20（MC 26.2 反混淆源码）+ 游戏随包 LWJGL 3.4.1 + Vulkan 1.4.357 头（refs/）+ glslang 16.2.0（mod/scripts/compile-spirv.sh）+ Checkstyle 14.1.0 / ErrorProne 5.1.1 + RTX 5090 + VVL（标准/全开双档）。

**Spec:** `docs/superpowers/specs/2026-09-16-greedy-mesh-pivot-design.md`（7 项裁决 + 红线 v2 + §4.2 缺数据/触发/成本分档规则）；机制基线：`docs/superpowers/specs/2026-09-15-s2-lod-ladder-design.md` §4/§5/§6；读码事实：`docs/s2-code-reading-notes.md` / `docs/s1-code-reading-notes.md`。

## Global Constraints

- baseline = Vulkan 2026 里程碑 + `VK_EXT_descriptor_heap`，硬门槛自禁用（不兜底）；硬门槛禁用时本计划全部机制随之禁用，游戏照常运行；
- pivot 增量对官方代码零新增触碰点（规格 §3 触碰面台账）：atlas sampler 入我方 BGL（句柄只读取自官方 GpuTexture 对象）；tint / 精灵矩形表 = 只读消费官方数据；任何新钩子先登记台账；
- 每闸：`:mod:build` + lint + `:mod:test` 绿 → VVL 标准档 + 全开档两轮双零 → 用户目验裁决（在机前）→ 归档 `docs/s2-acceptance/<step>/`（日志 + 截图 + ≤5 行中招清单）→ commit（中文、一闸一条）；
- 预算：任务1~6 far pass ≤1ms，任务7（10km）≤2ms；超预算 = 明确 `farpass budget breach` 日志行（只报不改行为）；CPU：帧级剔除 ≤0.2ms，L3 分帧构建 ≤2ms/帧（初值）；
- 红线 v2（规格 §5）：R-a 注册表/堆表唯一权威 / R-b tile 同源 + 1 层 margin / R-c 堆子区 = spike 实测最坏 × 1.5（只报警不兜底）/ R-d DAG 接口定死 / R-e SoA 布局 GPU-writable / R-f 不 bake 单层天空线；
- 缺数据规则（规格 §4.2，已裁）：L1 cell 须辖下 ≥90% L0 tile 有数据才可建（L2/L3 继承：≥90% 子级 cell 已建）；缺样本当空气参选（64 票含缺席票，平手 → 实心）；驱逐滞后带内数据不算缺失；
- 重建触发（规格 §4.2，已裁）：重提取 ⟺ tile generation 变化；重派生 ⟺ 子级网格变化；带内进出只改 draw 列表 + LRU 驻留态（只重上传）；
- 读码先决纪律：凡涉及官方内部先读码回填 `docs/s2-code-reading-notes.md` 再写码；笔记与代码冲突以笔记为准；
- 任何 gradle 命令前 `bash mc-src/scripts/serve-mappings.sh` 必须存活（127.0.0.1:8726）；
- 跑机命令固定：`./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation"`（后台 job + tee，日志标签 `s2v2-run-<step>-<HHMMSS>`）；
- shader 改动必须经 `mod/scripts/compile-spirv.sh`（glslang 16.2.0 + spirv-val）出 .spv，与 .vsh/.fsh 同 commit 归档；
- subagent 一次只许 1 个（全局规矩）；执行协议同 S1/S2：栞问 → 栞启动 → 用户盯屏裁决 → 归档 → commit。

## File Structure（分解决定在此焊死）

**新建**（`mod/src/main/java/dev/dhvk/` 下）：
- `mesh/CellKey.java` — cell 坐标 (ix, iy, iz, level)，唯一坐标类型（自 FarTerrainRenderer 现有内部结构抽出）
- `mesh/CellMeshExtractor.java` — 接口（R-d）
- `mesh/CellGeometryObserver.java` — 接口（S4 出货口门，只登记不实现）
- `mesh/BlockTileView.java` — 32³ tile 读视图（CPU 镜像，R-b）
- `mesh/HeapSubregion.java` — 堆子区字节视图（SoA 区域偏移表，规格 §5.1）
- `mesh/BlockTileBuilder.java` — chunk 方块数据 → 32³ tile（与 GPU 上传同源）
- `mesh/CpuGreedyMeshExtractor.java` — 二进制贪心 mesher（任务1 主体；任务3 切 SoA v2 全布局）
- `mesh/LodVote.java` — 级联多数投票（规格 §4.2，含 90% 可建阈值 + 缺样本当空气）
- `mesh/CellBuildBudget.java` — 分级帧建/驱逐预算 + L3 分帧渐进调度 + 自动调节闭环接口
- `mesh/SpriteRectTable.java` — spriteID→atlas rect 表（构建 + 上传，任务3）
- `mesh/L0ColumnTopIndex.java` — L0 逐列顶面预计算（帧级剔除视线路径 O(1) 查表，任务5）
- `mesh/CellOcclusionCuller.java` — 构建期点剔除 + 帧级区域剔除（任务5）

**修改**：
- `FarTerrainRenderer.java`（928 行单体载体）— 任务1 提取器接线；任务2 分级注册表/带宽表/帧预算；任务3 DHVK_TEX 开关 + sampler 组；任务5 剔除调用点；**任务2 后若 >1200 行，注册表/流式部分拆入 `registry/`（任务内拆分，不另立重构）**
- `heap/DescriptorHeap.java` / `heap/HeapLayout.java` — R-c 预算常数（任务1 实测定死）/ 分级子区规划
- `assets/dhvk/shaders/core/far_terrain.vsh` / `far_terrain.fsh` (+.spv) — 任务1 不改（复用 S1 28B pos+color 布局，纯色分支 = 现有路径）；任务3 全 SoA + atlas sampler；任务4 AO 压暗
- `DhvkQueryPool.java` — 不动（N=10 已就位，分级槽位已预摊）

**测试**（新建，`mod/src/test/java/dev/dhvk/mesh/`）：`BlockTileBuilderTest.java`、`CpuGreedyMeshExtractorTest.java`、`LodVoteTest.java`、`SpriteRectTableTest.java`、`CellOcclusionCullerTest.java`；扩展既有 `mod/src/test/java/dev/dhvk/HeapLayoutTest.java`

**文档**：`docs/s2-code-reading-notes.md`（读码回填）/ `docs/s2-acceptance/`（逐闸归档）/ 总计划 §6（状态回填）/ `docs/mc26.2-vk-reference.md`（ndcz 公式，任务7）

---

## Task 0: 任务0' 侦察 + 接口壳

**目标**：读码先决六项（规格 §11 任务0'）全部销账 + 参照仓库 clone + 接口壳入码，spike 进入"直接可写码"状态。

**Files:**
- Modify: `docs/s2-code-reading-notes.md`（回填 §10.1~§10.7）
- Create: `mod/src/main/java/dev/dhvk/mesh/CellKey.java`、`CellMeshExtractor.java`、`CellGeometryObserver.java`、`BlockTileView.java`、`HeapSubregion.java`
- Reference: clone `refs/binary-greedy-meshing`

**Interfaces:**
- Produces（后续任务消费）：`CellKey(int ix, int iy, int iz, int level)`（level 0=L0/32, 1=L1/128, 2=L2/512, 3=L3/2048；`sideBlocks() = 32L << (2*level)`）；`CellMeshExtractor.extract(CellKey, BlockTileView, HeapSubregion)`；`CellGeometryObserver.onCellUpdated(CellKey, HeapSubregion, long generation)` / `onCellEvicted(CellKey)`；`BlockTileView`（isSolid/spriteId/tintRgb/hasData，32³ 语义 + margin 按笔记 §10.1 裁决）；`HeapSubregion`（SoA 区域偏移表 + 写窗口）。

- [ ] **Step 1: 环境确认**

```bash
bash mc-src/scripts/serve-mappings.sh &   # 保持存活（127.0.0.1:8726）
git status --short   # 预期：无输出（干净）
```

- [ ] **Step 2: 读码 33×33 margin（笔记 §10.1）**

读 `mod/src/main/java/dev/dhvk/VoxelWallSynthesizer.java` 与 `FarTerrainRenderer.java` 几何生成段：S1 33×33 网格是否含 1 列 margin？回填笔记并定裁（初值 = tile 32³ 本体 + 面剔除时按需取邻 tile 邻边，邻 tile 缺失该侧按空气；不存 33³）。

- [ ] **Step 3: 剔除算术落数（笔记 §10.2）**

纸面计算（官方默认 fov 从 `docs/mc26.2-vk-reference.md` §2 取）：10km 带各级可见 cell 数 / GPU 驻留数 / 堆表总尺寸（R-c v2 预算后）vs maxResourceHeapSize 的关系。结果入笔记，不加代码。

- [ ] **Step 4: maxResourceHeapSize 数值到手（笔记 §10.3）**

查 `docs/s2-acceptance/logs/` 的 S2 步骤1 运行日志是否已 dump `maxResourceHeapSize`；无则在 init dump 处加一行（本任务唯一新代码）跑一次取数入笔记。

- [ ] **Step 5: visibility 双通道核对（笔记 §10.4）**

S1 笔记 §8.2 双通道实证 vs 现代码：堆侧 visibility 直写路径 + 官方 `writeChunkSections` 壳路径（`DynamicUniforms.java:61/65`）都在、写序（堆先壳后）。

- [ ] **Step 6: 官方 terrain atlas 绑定路径（笔记 §10.5，关键项）**

```bash
grep -rn "terrain" mc-src/client/src/main/java/net/minecraft/client/gl/ --include="*.java" | grep -i "sampler\|texture\|atlas" | head -30
```

定位官方 Vulkan chunk 管线中 atlas sampler 的 BGL 形态与 GpuTexture 句柄获取链；回填笔记并定裁：我方 BGL 能否引用同一 sampler 描述符集，不能 → 自建 sampler 组（probe 句柄 + 我方 BGL，零官方触碰点，规格 §3 退路）。

- [ ] **Step 7: 26.2 精灵/染色 API（笔记 §10.6）**

找官方 chunk mesh 构建里"方块 → 精灵 UV 矩形 + biome 染色"的解析链（26.2 反混淆名，BakedModel/BlockColors/ColorResolver 等价物），回填 mod 侧可调的确切 API（入参 BlockState/位置；出参 spriteID/UV rect、tint rgb）——任务1 颜色源、任务3 UV/tint 源的同一来源。

- [ ] **Step 8: clone 参照仓库 + 读算法核心（笔记 §10.7）**

先 ask_user_question 确认（名称 cgerikj/binary-greedy-meshing / 来源 github / 用途 = 贪心 mesher 算法参照），点头后：`git clone https://github.com/cgerikj/binary-greedy-meshing refs/binary-greedy-meshing`。读算法核心（occupancy bitmask 布局 / 八叉树合并规则 / 索引输出格式），特别确认"只合并同贴图相邻面"（MC 化改造点 2）的映射与我方 HeapSubregion SoA 的输出对应关系。

- [ ] **Step 9: 接口壳五个小文件**

```java
// CellKey.java
package dev.dhvk.mesh;
/** Cell 坐标 (ix, iy, iz, level)。level: 0=L0(32) 1=L1(128) 2=L2(512) 3=L3(2048)。唯一坐标类型。 */
public record CellKey(int ix, int iy, int iz, int level) {
    public long sideBlocks() { return 32L << (2L * level); }
    public long originX() { return (long) ix * sideBlocks(); }
    public long originY() { return (long) iy * sideBlocks(); }
    public long originZ() { return (long) iz * sideBlocks(); }
}

// CellMeshExtractor.java
package dev.dhvk.mesh;
/** Mesher 接口（红线 R-d：执行器可换、DAG 不变）。v2 实现 = CpuGreedyMeshExtractor；
 *  第三实现位预留 S4（GPU compute / shader enqueue）——调用方一行不改。 */
public interface CellMeshExtractor {
    /** 提取 cell 表面几何写入堆子区。同步（CPU 侧）、只写 out 不改 tile。
     *  输出字节布局 = 规格 §5.1 SoA（任务1 spike 期用 28B 子集：pos f32×3 + color f32×4 + IBO 顺序 u32）。 */
    void extract(CellKey key, BlockTileView tile, HeapSubregion out);
}

// CellGeometryObserver.java
package dev.dhvk.mesh;
/** 几何变更观察者（S4 RTAS 出货口门；现在只登记不实现——届时 Caustica 集成实现它：
 *  注册表 diff → BLAS/TLAS。绑定层备注见规格 §10②：AS 描述符与现有描述符共享同一 heap 写路径。 */
public interface CellGeometryObserver {
    void onCellUpdated(CellKey key, HeapSubregion region, long generation);
    void onCellEvicted(CellKey key);
}

// BlockTileView.java
package dev.dhvk.mesh;
/** 32³ block tile 读视图（CPU 镜像，与 GPU 上传同源，红线 R-b）。
 *  occupancy = "非 air 且可见"（opaque + cutout）；spriteID/tint 另查。
 *  margin 语义按笔记 §10.1 裁决（初值 = 按需邻 tile 取数，不存 33³）。 */
public interface BlockTileView {
    int SIDES = 32;
    boolean isSolid(int x, int y, int z);
    int spriteId(int x, int y, int z);   // u16 范围
    int tintRgb(int x, int y, int z);    // packed rgb8
    boolean hasData();                    // false = 从未产生（规格 §4.2 缺数据规则前置）
}

// HeapSubregion.java
package dev.dhvk.mesh;
/** 堆子区字节视图（红线 R-c：预算预摊不搬家；布局 = 规格 §5.1 SoA，GPU 端也能直接写，
 *  禁止 Java 对象 / List 中间表示）。 */
public final class HeapSubregion {
    private final long baseOffset;      // resource heap 内偏移（字节）
    private final int vertexCapacity;   // 顶点容量（R-c 预算，任务1 实测定死）
    private final int posOffset;        // SoA: pos f32×3
    private final int colorOffset;      // SoA: color f32×4（任务1 子集 = R-e v1 形）
    private final int uvAbsOffset;      // SoA: uvAbs f32×2（任务3 启用）
    private final int spriteOffset;     // SoA: spriteID u16（任务3）
    private final int aoOffset;         // SoA: ao u8（任务4）
    private final int tintOffset;       // SoA: tint rgb8（任务3）
    private final int normalOffset;     // SoA: normal u8（任务3）
    private final int indexOffset;      // IBO 区（u32 顺序索引：三角汤 = 3i/3i+1/3i+2，复用 S1 drawIndexed 路径零改渲染器）
    // 构造器 + 区域写窗口方法（writeFloatArray / writeInt / writeByte... 按对齐）
}
```

- [ ] **Step 10: 编译 + lint**

```bash
./gradlew :mod:build :mod:lint 2>&1 | tail -20   # 预期 BUILD SUCCESSFUL
```

- [ ] **Step 11: commit**

```bash
git add mod/src/main/java/dev/dhvk/mesh/ docs/s2-code-reading-notes.md
git commit -m "S2v2 任务0': 读码先决六项销账(margin/剔除算术/heapSize/visibility双通道/atlas绑定/精灵染色API) + 参照仓库 + 接口壳(CellKey/CellMeshExtractor/CellGeometryObserver/BlockTileView/HeapSubregion)"
```

---

## Task 1: 贪心 spike（闸1）+ 高度场退役

**目标**："贪心几何进 heap 渲染出来"成为事实：一个真实混合 chunk（含树/悬崖/洞口）纯色渲染、VVL 双零、秒表数、R-c 预算数字定死；过闸后高度场退役。

**Files:**
- Create: `mod/src/main/java/dev/dhvk/mesh/BlockTileBuilder.java`、`CpuGreedyMeshExtractor.java`
- Create: `mod/src/test/java/dev/dhvk/mesh/BlockTileBuilderTest.java`、`CpuGreedyMeshExtractorTest.java`
- Modify: `FarTerrainRenderer.java`（提取调用点接 `CellMeshExtractor`；旧高度场路径留 `DHVK_MESH=height` 因子开关至过闸）
- Modify（若实测超）: `heap/DescriptorHeap.java`（R-c 预算常数）

**Interfaces:**
- Consumes: 任务0 全部接口；S1 heap/arena/draw 路径；笔记 §10.1/§10.6 结论。
- Produces: `BlockTileBuilder.build(CellKey) → BlockTileView`（与 GPU 上传同源）；`CpuGreedyMeshExtractor`（任务1 输出 = VBO 28B pos+color + IBO 顺序 u32）；R-c 预算常数。

- [ ] **Step 1: 写 BlockTileBuilderTest（先失败）**

测试输入 = 确定性方块阵列（经笔记 §10.6 的最小方块数据源抽象注入）：
1. `testTileFromSolidPattern`：下半实心/上半空气 → isSolid 逐列正确、hasData()=true、spriteId/tint 按注入值；
2. `testTileMissing`：全 air 从未产生 → hasData()=false（§4.2 缺数据规则前置）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :mod:test --tests "dev.dhvk.mesh.BlockTileBuilderTest" 2>&1 | tail -15   # 预期 FAIL
```

- [ ] **Step 3: 写 BlockTileBuilder**

tile 字节格式 = S1 的 GPU 上传同源格式（R-b 调色板索引量化）；isSolid = 非 air 且可见（opaque + cutout）；spriteId/tint 解析链 = 笔记 §10.6 API；margin = 笔记 §10.1 裁决。

- [ ] **Step 4: 跑测试确认通过**（`./gradlew :mod:test --tests "dev.dhvk.mesh.BlockTileBuilderTest"` 预期 PASS 2 tests）

- [ ] **Step 5: 写 CpuGreedyMeshExtractorTest（先失败）**

ground truth = 手算小图案 + 参照仓库算法对拍（笔记 §10.7）：
1. `testSingleColumn`：空气中 1×1×32 实心柱 → 露面 = 柱 4 侧 + 顶；断言三角形数与坐标在 cell 内；
2. `testGreedyMerge`：4×4×1 实心板 → 4 侧合并成大四边形（断言输出面数 < 不合并基准，UV 轴属性正确）；
3. `testNoMergeDifferentSprite`：相邻两块不同 spriteID → 不合并不越界；
4. `testMissingDataAir`：半 tile 缺失 → 缺失部分按空气（§4.2 规则）。

- [ ] **Step 6: 跑测试确认失败**（预期 FAIL）

- [ ] **Step 7: 写 CpuGreedyMeshExtractor**

算法 = 笔记 §10.7 + 规格 §6 MC 化改造（bitmask 面剔除 → 八叉树贪心合并（仅同 sprite + 同 tint）→ 六方向全出面（含侧/底面，崖壁/悬挑/洞口天然可读）→ 四边形展三角）；输出 VBO SoA（pos f32×3 cell 相对 + color f32×4 = tint × 精灵基色，28B 子集）+ IBO 顺序 u32 → 写 HeapSubregion；三角形数/顶点数写日志（R-c 实测数据源）。

- [ ] **Step 8: 跑测试确认通过**（预期 PASS 4 tests）

- [ ] **Step 9: 接入 FarTerrainRenderer + 全量构建**

提取调用点接 `CellMeshExtractor`（默认 = CpuGreedyMeshExtractor；`DHVK_MESH=height` 保留旧路径至过闸）；tile 构建走 BlockTileBuilder（与 GPU 上传同源）；上传/draw/heap 绑定 = S1 路径零改。`./gradlew :mod:build :mod:lint` 绿。

- [ ] **Step 10: VVL 标准档跑机（用户选点）**

栞启动：`./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation" 2>&1 | tee /tmp/s2v2-run-spike-$(date +%H%M%S).log`（后台 job）。用户在游戏里选**含树/悬崖/洞口的混合 chunk**，站到 500+ blocks 外让远带接管，报世界坐标归档。收集：VVL error 数（预期 0）+ 秒表行（far pass µs + mesher CPU µs + 三角形数）。

- [ ] **Step 11: VVL 全开档跑机**（同命令全开档参数，同 S2 步骤1 姿势；预期双零 + 干净退出）

- [ ] **Step 12: 用户目验裁决（形状三项，闸门）**

用户机前逐项裁决：① 崖壁可读（不是平滑坡）；② 树 = 色块（而非消失变秃）；③ 洞口可见。截图归档 `docs/s2-acceptance/step1-spike/`。**任一形状项失败 = 重开 pivot 讨论**（规格 §7 失败裁决，高度场退役裁定被目验推翻）→ 本任务暂停等新裁决；全过则继续。

- [ ] **Step 13: 四类 chunk 最坏量实测 → R-c 定死**

用户依次访问平地/山地/森林/洞穴口（或确定性合成图案），各取最大顶点/索引数；**R-c 预算 = 全局最坏 × 1.5** 写入 DescriptorHeap/HeapLayout 预算常数（超 S1 预摊子区尺寸 → 放大堆 init 常数，独立 commit）；实测表入 `docs/s2-acceptance/step1-spike/rc-budget.md`。

- [ ] **Step 14: 裁决记录 + 归档 + commit**

`docs/s2-acceptance/step1-spike/acceptance.md`（R-e/R-c 裁决记录 = 规格 §5 + 实测值回填 + ≤5 行中招清单）；`git add -A && git commit -m "S2v2 任务1(贪心 spike)过闸: 形状三项目验 + VVL 双零 + far pass X µs + R-c 预算定死(实测最坏 Y × 1.5); 高度场退役下条 commit"`

- [ ] **Step 15: 高度场退役（独立 commit）**

删 S1 高度场提取路径（DHVK_MESH 开关 + 高度场提取函数）；**保留合成调试环**（VoxelWallSynthesizer + DHVK_NOWALL/NOPROBE，规格 §0：与退役无关）；`:mod:build` + lint 绿 + VVL 标准档快速冒烟；`git commit -m "高度场退役: cell 高度场提取路径删除(目验基线 = run45-50 归档截图); 合成调试环保留为机制验收工具"`

---
## Task 2: LOD 阶梯（体素版）（闸2）

**目标**：L0~L3 全阶梯跑起来：级联投票 + 逐级贪心 + 带宽表 + 分级帧预算 + per-cell UBO 并入堆；价目表 L0~L3 分行；派生成本分档硬指标。

**Files:**
- Create: `mod/src/main/java/dev/dhvk/mesh/LodVote.java`、`CellBuildBudget.java`
- Create: `mod/src/test/java/dev/dhvk/mesh/LodVoteTest.java`
- Modify: `FarTerrainRenderer.java`（分级注册表/带宽表/帧排序分组/分级秒表槽）、`heap/HeapLayout.java`（分级子区规划）、`heap/DescriptorHeap.java`（任务1 预算常数）

**Interfaces:**
- Consumes: BlockTileBuilder / CpuGreedyMeshExtractor（任务1）；S2 v1 规格 §3.1/§3.2 带宽表/防抖/自动调节闭环。
- Produces: `LodVote.deriveL1Grid(CellKey, BlockTileView[] of 64) → 32³ 网格`；`LodVote.deriveChildGrids(...)`（L2/L3，L3 分帧渐进）；`CellBuildBudget`（分级帧建/驱逐预算 + 自动调节闭环接口）。

- [ ] **Step 1: 写 LodVoteTest（先失败）**

1. `testL1VoteAllSolid`：64 tile 全实心 → L1 网格全实心（sprite/tint = 多数）；
2. `testL1VoteMissingAir`：64 中 32 缺失 → 缺席票 = 空气（32 实 vs 32 气 → 平手 → 实心，§4.2 规则）；
3. `testBuildabilityThreshold`：57/64 有数据（89.06% <90%）→ cell 不建；58/64（90.6%）→ 可建（90% 边界行为确定）；
4. `testL2SingleGridVote`：L2 网格点 = 单个 L1 网格内 4×4×4 多数（偏移 4x%32 公式）；
5. `testDeterministicTie`：同输入两次运行输出字节一致（平手规则确定可复现）。

- [ ] **Step 2: 跑测试确认失败**（`./gradlew :mod:test --tests "dev.dhvk.mesh.LodVoteTest"` 预期 FAIL）

- [ ] **Step 3: 写 LodVote**

规格 §4.2 公式：L1 网格点 (x,y,z) = 64 个 L0 tile 在 ((4x)%32,(4y)%32,(4z)%32) 处体素的多数（每 tile 恰 1 样本，64 次查表）；L2/L3 = 单个子级网格内 4×4×4 多数（偏移 4x%32，子级网格派生即用）；缺数据规则实现于此（90% 可建阈值 = 参数常量 BUILD_THRESHOLD_PCT，初值 90，目验可调）；L1+ 网格派生即用（派生后弃；驻留缓存退路不预写，YAGNI——seam = 调用侧可缓存返回值）。

- [ ] **Step 4: 跑测试确认通过**（预期 PASS 5 tests）

- [ ] **Step 5: CellBuildBudget + 分级注册表接入**

`CellBuildBudget` = 分级帧建/驱逐预算（初值：建 L0 ≤8 / L1 ≤4 / L2 ≤2 / L3 ≤2；驱逐 L0 ≤2 / L1 ≤2 / L2 ≤1 / L3 ≤1；驱逐滞后 2 帧；带宽切换 ±20% 防抖）+ **L3 分帧渐进调度**（每帧 ≤2ms CPU 初值，秒表自动调节闭环接管：超预算自最远档 ×0.8（floor），富余 ×1.1（cap））+ 触发规则（重提取 ⟺ generation；重派生 ⟺ 子级网格；带内进出只改 draw 列表 + LRU，只重上传）。FarTerrainRenderer：分级注册表（CellKey 带 level）+ 分级带宽表（S2 v1 §3.1：近带 S1 表 [448,2048) L0/L1；10km 表任务7 启用）+ 帧 draw 列表分级排序分组 + 分级秒表槽（N=10 池已预摊）。`:mod:build` + lint 绿。

- [ ] **Step 6: per-cell UBO 并入堆（S2 v1 §3.3 原样）**

`ChunkSectionInfo` 成堆内描述符（cell 子区独立槽位，`HEAP_WITH_CONSTANT_OFFSET`）；per-frame visibility = 4B 直写堆窗口；官方 `writeChunkSections` 通道退役为 push 壳（壳数据写同一 visibility 值）；**数据源判别探针**：堆写 TextureSize=(7,7)、壳=(1,1) → 探针板读哪边判数据走哪边（S1 判别式原样）。`:mod:build` 绿。

- [ ] **Step 7: VVL 标准档 + 全开档两轮跑机**

两次 `./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation"`（日志 `s2v2-run-ladder-a/b-<HHMMSS>`）。预期：双零 + 干净退出（dispose 纪律覆盖新对象）。

- [ ] **Step 8: 价目表 + 派生成本硬指标（闸数据）**

日志收集：价目表 L0~L3 分行（各级 far pass µs + cell 数）合理；**L1/L2/L3 网格首次派生成本**（L3 分帧填充时间以秒计落日志）；far pass 总 ≤1ms。L1 >5ms 或 L2 >50ms → 不过闸：优化或触发驻留缓存裁决（§4.2 退路）。

- [ ] **Step 9: 用户目验裁决（闸）**

近带切片 + 合成调试环（探针板/彩虹墙）：L0/L1 交界无 pop-in（visibility 淡入）；阶梯剪影连续。截图归档 `docs/s2-acceptance/step2-ladder/`。

- [ ] **Step 10: 归档 + commit**

验收报告 + 中招清单；`git add -A && git commit -m "S2v2 任务2(LOD阶梯体素版)过闸: L0~L3 价目表 + 派生成本分档(L1 X ms / L2 Y ms / L3 填充 Zs) + UBO 数据源=堆(判别探针) + VVL 双零"`

---

## Task 3: 贴图 / UV + tint（闸3）

**目标**：`DHVK_TEX=1`：官方 terrain atlas 真贴图 + 按方块 UV 平铺 + biome 染色；SoA v2 全布局生效（R-e v2 ABI 全面生效）。

**Files:**
- Create: `mod/src/main/java/dev/dhvk/mesh/SpriteRectTable.java` + `mod/src/test/java/dev/dhvk/mesh/SpriteRectTableTest.java`
- Modify: `far_terrain.vsh` / `far_terrain.fsh` (+.spv)、`CpuGreedyMeshExtractor.java`（SoA v2 输出）、`FarTerrainRenderer.java`（sampler 组 + 表上传 + DHVK_TEX 开关）、`HeapSubregion.java`（启用全 SoA 区）

**Interfaces:**
- Consumes: 笔记 §10.5/§10.6（atlas 绑定路径 + 精灵/染色 API）；任务1 tile（spriteID/tint 已在 tile 内）。
- Produces: shader `in uvAbs/spriteID/tint` + sampler 分支；`SpriteRectTable`（spriteID→rect，per-pass 上传）。

- [ ] **Step 1: 写 SpriteRectTable + 测试（先失败 → 通过）**

表 = 从官方 atlas 布局构建（只读消费，笔记 §10.5/§10.6 链：spriteID → UV rect 含 padding）；测试断言 rect ⊂ [0,1]、padding ≥0、表大小 = 在用精灵数且 < u16 容量（风险 N5 项）。`:mod:test --tests "dev.dhvk.mesh.SpriteRectTableTest"` 先 FAIL 后 PASS。

- [ ] **Step 2: shader 手术（vsh/fsh + .spv）**

`far_terrain.vsh` 加 `in vec2 uvAbs; in uint spriteID; in vec3 Tint;`（顶点输入布局 = SoA v2 规格 §5.1；S1 幻象绑定/判别式不动）；`far_terrain.fsh` 加 sampler（官方 terrain atlas，绑定路径 = 笔记 §10.5 裁决：共享官方 BGL 或自建 sampler 组）+ UV 平铺规则（规格 §5.2：未展开量 + shader 端取模 = 按方块平铺不拉花）：

```glsl
vec4 r = spriteRects[spriteID];        // (x0, y0, w, h)：SpriteRectTable per-pass 表
vec2 uv = r.xy + fract(uvAbs) * r.zw;  // y 翻转 / padding 细节按笔记 §10.5 官方约定
vec4 texc = texture(u_terrainAtlas, uv) * Tint;
```

`DHVK_TEX=0` 分支保留 vertexColor 路径（S1 脸，A/B 对照用）；`=1` 走 texc。`mod/scripts/compile-spirv.sh` 编译（glslang 16.2.0 + spirv-val 过）。

- [ ] **Step 3: 接线 + 全量构建**

FarTerrainRenderer：`DHVK_TEX`（env，本任务后默认 1）；SpriteRectTable per-pass 上传（路径按笔记 §10.5 裁决）；CpuGreedyMeshExtractor 输出升 SoA v2（uvAbs = 面轴世界坐标由 mesher 写入，轴向由面方向定；spriteID/tint 来自 tile）。`:mod:build` + lint 绿。

- [ ] **Step 4: VVL 标准档 + 全开档两轮跑机**（预期双零——sampler 组是我方管线自己的对象）

- [ ] **Step 5: 用户放大 A/B 目验裁决（闸）**

用户走近远带放大看贴图：① 无拉花（贪心合并大四边形内按方块图案正确）；② 无轴错（各面 U/V 轴向正确）；③ 无 bleed（精灵边缘不渗邻精灵）；④ tint 正确（草绿 / 水边色对）；⑤ `DHVK_TEX=0/1` A/B 对照：贴图面明显比纯色面信息多。截图归档 `docs/s2-acceptance/step3-texture/`。

- [ ] **Step 6: 预算 + 归档 + commit**

far pass ≤1ms 确认（采样无显著增量，秒表数落日志）；`git add -A && git commit -m "S2v2 任务3(贴图/UV)过闸: 官方 atlas 真贴图 + 按方块 UV 平铺 + biome 染色，放大 A/B 目验(无拉花/无轴错/无bleed) + VVL 双零 + 1ms 预算内; R-e v2 SoA 全布局生效"`

---

## Task 4: 烘焙 AO（闸4）

**目标**：逐顶点烘焙 AO（0..3）压暗生效，远景立体感（崖壁凹凸、树块体积）。

**Files:**
- Modify: `CpuGreedyMeshExtractor.java`（AO 计算：逐顶点 3 邻角实心数 → 0..3，经典 MC AO，纯几何邻查询；L1+ 级用降采样网格，算法同一）、`far_terrain.fsh`（AO 压暗曲线）、`HeapSubregion.java`（启用 ao 区）

**Interfaces:**
- Consumes: 任务3 SoA v2 全布局；tile occupancy。
- Produces: 带 AO 的 SoA 输出；fsh 压暗（`col *= 1.0 - ao * K`，K 初值 0.15，目验调）。

- [ ] **Step 1: 加测试用例（先失败）**

CpuGreedyMeshExtractorTest 加 `testAoSidesAndCorner`：顶点两侧均实心 + 角实心 → AO=3（最暗）；一侧实心 → AO=1（经典 MC AO 规则：side1/side2/corner 三邻角，全实心=3）。

- [ ] **Step 2: 跑测试确认失败**（预期 FAIL 新用例）

- [ ] **Step 3: 实现 AO（extractor 计算 + SoA ao 区写入 + fsh 压暗曲线 K=0.15）**

- [ ] **Step 4: 跑测试确认通过**（预期 PASS 全用例）

- [ ] **Step 5: VVL 两轮跑机**（预期双零——AO 是纯顶点数据 + shader 算术，无新 Vulkan 对象）

- [ ] **Step 6: 用户目验裁决（闸）：立体感**

崖壁有明暗（凹凸可读）、树块有体积；压暗不过强（整体发灰黑 → K 调小）。截图归档 `docs/s2-acceptance/step4-ao/`。

- [ ] **Step 7: 归档 + commit**

`git add -A && git commit -m "S2v2 任务4(烘焙AO)过闸: 逐顶点 AO 压暗(K 初值 0.15) + VVL 双零 + 立体感目验(崖壁明暗/树块体积)"`

---

## Task 5: 遮挡剔除（闸5）

**目标**：S2 v1 规格 §4 原样（两层均 CPU 侧）：构建期点剔除（体素适配："同级邻 tile 有更高实心则剔"，具体规则读 DH FullDataOcclusionCuller 源定稿）+ 帧级区域剔除（5 点可见性，视线路径 = L0 逐列顶面表，≤0.2ms）。

**Files:**
- Create: `mod/src/main/java/dev/dhvk/mesh/CellOcclusionCuller.java`、`L0ColumnTopIndex.java` + `mod/src/test/java/dev/dhvk/mesh/CellOcclusionCullerTest.java`
- Modify: `FarTerrainRenderer.java`（点剔除 = 提取阶段内规则；区域剔除 = draw 列表前）

**Interfaces:**
- Consumes: 任务1 tile / L0 顶面数据；S2 v1 规格 §4.2（5 点可见 = 可见；缓存按 (cell, generation, camera 256 格位置) stamp，相机移动 ≥16 blocks 或 generation 变化才重算；被剔 cell 不进本帧 draw 列表但驻留保留、LRU 不刷新）。
- Produces: `CellOcclusionCuller.frameCull(camera, cells) → 可见集`（CPU ≤0.2ms）；`L0ColumnTopIndex`（tile 构建时预计算，视线路径 O(1) 查表，风险 N6 退路）。

- [ ] **Step 1: 读 DH FullDataOcclusionCuller 源（笔记 §10.8）**

`grep -rn "class FullDataOcclusionCuller" refs/dh/` → 读该类 + 调用处，回填精确规则（±X/±Z 四向视线的采样密度）→ 体素适配裁决（初值）：cell 顶面顶点 p 被剔 ⟺ ±X/±Z 四向任一方向上，本 cell + 邻 cell 边缘带（1 block 外沿，笔记 §10.1 margin 裁决）内存在更高实心柱；输出 = 保留顶点、重建索引（子区复用，R-c 预算覆盖）。

- [ ] **Step 2: 写 L0ColumnTopIndex + 测试**

tile 构建时预计算逐列顶面高度（32×32/tile）；视线路径 = 沿 XZ 步进 16~64、≤20 采样、O(1) 查表。测试：确定性地形（抛物面），5 点可见性判定手算对拍。

- [ ] **Step 3: 写 CellOcclusionCullerTest（先失败）**

① 被更高山脊完全遮挡的 cell → 被剔（不进 draw 列表，驻留保留）；② 山脊移除（generation+1）→ 次帧立即恢复（零 pop-in）；③ 缓存有效：相机移动 <16 blocks 不重算（断言重算次数）；④ 5 点规则：5 点仅 1 点可见 → 仍可见（保守防洞）。

- [ ] **Step 4: 跑测试确认失败**（预期 FAIL）

- [ ] **Step 5: 实现两层剔除 + 接线**

点剔除进 CpuGreedyMeshExtractor 提取阶段（重建索引）；区域剔除进 FarTerrainRenderer 每帧 draw 列表生成（缓存 stamp + 0.2ms 预算计时日志）；山地合成场景开关 `DHVK_MOUNTAIN`（确定性山地高度场加入合成调试环，S2 v1 规格 §4.3 原样）。

- [ ] **Step 6: 跑测试确认通过 + 全量构建**（全绿）

- [ ] **Step 7: 秒表 A/B（闸数据）：山地场景**

`DHVK_MOUNTAIN=1` 两轮：A = `DHVK_NOCULL=1`（剔除关），B = 开。日志对比：far pass µs + drawn cell 数（B 显著低）；帧级剔除 CPU ≤0.2ms（计时日志）。

- [ ] **Step 8: VVL 两轮跑机**（预期双零）

- [ ] **Step 9: 用户目验裁决（闸）：山地剪影无洞**

山地场景转视角：山脊剪影连续无洞（无过剔）；谷地场景该看见的看见（5 点保守规则防"身在谷中该见的被剔"）。截图归档 `docs/s2-acceptance/step5-cull/`。

- [ ] **Step 10: 归档 + commit**

`git add -A && git commit -m "S2v2 任务5(遮挡剔除)过闸: 点剔除(体素适配) + 帧级区域剔除(0.2ms 预算内) + 山地秒表A/B(far pass X→Y µs, drawn cells M→N) + VVL 双零 + 剪影无洞目验"`

---

## Task 6: VRS rate map（闸6）

**目标**：rate image 取代 S1 固定 2×2（S2 v1 规格 §5 原样：64×64 R8，每帧 CPU 生成 = 屏幕光线按 per-LOD 带价，pNext 手术点 = 官方 VkRenderingInfo 构建点，管线 FSR 态组合语义 = VVL 终裁）。顺带：Host Image Copies 读码（rate map 上传或可省中转层）。

**Files:**
- Modify: `FarTerrainRenderer.java`（rate map 生成 + 上传 + pass 实例侧 pNext）、`VulkanRenderPipelineSurgeryMixin`（S1 手术点原样，结构参数改）、（fallback 时）自建 raw vkCmdBeginRendering 路径

**Interfaces:**
- Consumes: 任务2 分级带宽表（rate 定价输入）；笔记 §10.9~§10.11 结论。
- Produces: per-frame 64×64 R8 rate map + pass 侧 FSR attachment；三组秒表数落档。

- [ ] **Step 1: 读码先决（笔记 §10.9~§10.11）**

① 官方 createRenderPass → `vkCmdBeginRendering` `VkRenderingInfo` 构建点（有无 pNext 注入？无 → fallback = 自建 raw vkCmdBeginRendering 复刻官方 renderingInfo，S1 管线自建先例，render pass 对象兼容性 VVL 验）；② rate image 规范文本（format/layout/usage 初值 R8 + GENERAL + TRANSFER_DST，组合语义 VVL 终裁）；③ 5090 FSR properties dump（支持 rate 集，8×8 是否暴露，dump 落日志）；④ Host Image Copies 能力（rate map 上传或改 host image copy 省 vkCmdCopyBufferToImage 中转层）。

- [ ] **Step 2: 写 rate map 生成 + 上传**

每帧（CPU）：64×64 R8 = 每 texel 一条屏幕光线（相机位 + fov，per-frame 参数在手）→ 该光线进入 L_i 带的距离 → rate 码（L0/L1 → 1×1，L2 → 2×2，L3 → 4×4；8×8 若暴露则 L3 用，初值裁定）；host-visible buffer + vkCmdCopyBufferToImage 上传（若步骤1 ④ 裁决成立则 host image copy 路径）。

- [ ] **Step 3: pass 侧 pNext + 管线态调整**

按步骤1 ① 裁决：pNext 注入（VkRenderingFragmentShadingRateAttachmentInfoKHR，sType 1000378000 挂 VkRenderingInfo）或自建 raw vkCmdBeginRendering fallback；管线侧 VkPipelineFragmentShadingRateStateCreateInfoKHR 参数调整（rate image 与固定率的组合语义 = VVL 终裁）。`:mod:build` + lint 绿。

- [ ] **Step 4: VVL 标准档 + 全开档两轮跑机**

预期双零（S1 固定率手术点原样，仅结构参数改）；named（rate image format/layout 不合规，S2 v1 R8 风险项）→ 按报错修回填笔记。

- [ ] **Step 5: 秒表三组数（闸主证据）**

三跑：A = rate map（开）；B = S1 固定 2×2（`DHVK_VRS=fixed`）；C = 全 1×1（`DHVK_VRS=none`）。日志收 far pass µs：预期序 C ≤ A ≤ B（rate map 省 fragment 工作，山地/森林场景省幅明显）。三组数入验收报告。

- [ ] **Step 6: 用户 A/B 目验裁决（闸）：粗糙度终裁**

L3 带 4×4/8×8 粗糙度（8×8 若暴露则 4×4 vs 8×8 对比）：可接受 / 太粗 → 终裁（8×8 若 5090 不暴露 → 封顶 4×4，运行时预算，非设备兜底）。截图归档 `docs/s2-acceptance/step6-vrs/`。

- [ ] **Step 7: 归档 + commit**

`git add -A && git commit -m "S2v2 任务6(VRS rate map)过闸: rate image 取代固定2×2 + VVL 双零 + 秒表三组(rate map X / 固定2×2 Y / 全1×1 Z µs) + 粗糙度目验终裁(封顶 N×N)"`

---

## Task 7: 远平面三件套 = 10km 总闸（S2 修订版收口）

**目标**：S2 v1 规格 §6 原样：① 远平面 hook（far' = max(官方 far, bandFar·k)，候选①/②读码裁）+ ② fog end 随 bandFar 扩展（Fog 块 3 字段覆写）+ ③ ndcz 重推导（参数化公式写 `docs/mc26.2-vk-reference.md`）；10km 带（L0~L3，带真贴图 + AO）填满地平线；**交界带 A/B 截图闸**（fogStart 卡在刚过精度带边界，边界前后一圈 A/B 截图——"让变假发生在雾里"）。

**Files:**
- Modify: `FarTerrainRenderer.java`（10km 带宽表启用 + fogStart 交界带参数）、远平面/fog hook（候选①/②读码裁决；台账 #7 = v1 已裁，零新增）、`docs/mc26.2-vk-reference.md`（ndcz 参数化公式 + far' 深度分辨率表）

**Interfaces:**
- Consumes: 任务1~6 全部资产（阶梯/贴图/AO/剔除/VRS/秒表）。
- Produces: 10km 视觉事实 + ndcz 公式入档 + 交界带 A/B 归档 + 总计划 §6 状态回填（S2 修订版闭合）。

- [ ] **Step 1: 读码先决（笔记 §10.12~§10.14）**

① `CameraRenderState.projectionMatrix` 构建点（候选①：far 改后 Camera 侧重建矩阵，官方 bob/effect 变换自动保留；候选② fallback：`RenderSystem.setProjectionMatrix` @Inject，从 slice 矩阵解析 near/fov → 只重算 m[10]/m[2][3]/m[3][3] 三项写回）——读码裁，初值 = ①；② `FogRenderer.updateBuffer` 单点确认 + Fog 块偏移复核（FogColor@0..15 / EnvironmentalStart@16 / EnvironmentalEnd@20 / RenderDistanceStart@24 / RenderDistanceEnd@28 / SkyEnd@32 / CloudsEnd@36，S0 资产，spvc 反射交叉验证偏移未变）；③ ndcz 参数化：照 `docs/mc26.2-vk-reference.md` §2 方法对 far'=f 重推反 Z 解析式（文档 `ndcz(d)=19.997/d+0.0031` 为 f=320 特例）+ far' 下 near/far 深度分辨率数值表。

- [ ] **Step 2: 实现远平面 hook + fog end 覆写**

按步骤1 裁决（初值 = 候选①）：far' = max(官方 far, bandFar·k)（k = 首跑标定：视锥角半径 = bandFar 的匹配系数）；near 不动（0.05）；fog end = 官方写完后覆写 `FogRenderDistanceEnd`/`FogSkyEnd`/`FogCloudsEnd` = bandFar（其余字段不动）；**fogStart = 卡在刚过精度带边界一点（交界带参数，初值首跑定，目验可调）**。`:mod:build` + lint 绿。

- [ ] **Step 3: ndcz 参数化公式写文档**

`docs/mc26.2-vk-reference.md` §2：参数化公式 + far' 深度分辨率数值表（10km cell 边缘 near/far 深度差、z-fight 裕量）。

- [ ] **Step 4: 首跑 + k 标定 + VVL 两轮**

跑机（日志 `s2v2-run-farplane-<HHMMSS>`）：10km 带宽表启用（L0 [fogStart,2048) / L1 [2048,8192) / L2 [8192,10240) / L3 [10240,bandFar)，bandFar 初值 ≈ 12288）；k 系数首跑标定（地平线填满条件 = far' 与视锥角几何匹配，记录标定过程）；VVL 标准/全开双零；帧提交/队列与基线 diff 干净。

- [ ] **Step 5: 10km 总闸数据（闸）**

秒表：far pass ≤2ms（L0~L3 全阶梯价目表合理）；与 vanilla 基线 diff 干净（帧提交/队列不变）；深度精度抽查：10km cell 边缘无抖动/无 z-fight（探针墙定点 + 目验）。

- [ ] **Step 6: 用户目验裁决（总闸核心）：地平线填满**

游戏内 10km 渲染距离转视角：**地平线被 L0~L3 全阶梯真贴图 + AO 远景填满**（无秃块、无硬边、剪影溶进雾）；交界带 A/B 截图圈（fogStart 前后 ±一档）："变假发生在雾里"成立。截图归档 `docs/s2-acceptance/step7-farplane/`（10/20km 对比截图留 S3 打磨素材）。

- [ ] **Step 7: S2 修订版收口：验收报告 + 状态回填 + commit**

写 `docs/s2-acceptance/step7-farplane/acceptance.md`（总闸全数据 + ≤5 行中招清单）；总计划 §6 当前状态回填（S2 修订版闭合 @commit）；`docs/mc26.2-vk-reference.md` 回填。

```bash
git add -A && git commit -m "S2v2 任务7(远平面三件套=10km总闸)过闸: 10km 带 L0~L3 全阶梯真贴图+AO 填满地平线(目验) + far pass ≤2ms + VVL 双零 + 基线 diff 干净 + 深度精度抽查 + 交界带A/B(fogStart=X) + ndcz 参数化公式入档 → S2 修订版(greedy-mesh pivot)全部闭合"
```

**S2 修订版七闸全闭合** → 下一切片 = S3（瘦身：距离 alpha ramp / ≥32km ModelOffset / 地曲率可选 / 截图打磨）或 S4（留门 + 出货口，仅接口文档，零代码）——新会话裁。

---

## Self-Review 记录（writing-plans 要求）

- **Spec 覆盖**：规格 §3 触碰面台账 → Global Constraints + 各任务"零新增官方触碰点"；§4.2 缺数据/触发/成本分档 → Global Constraints + 任务2 Step 3/5/8；§5 红线 v2 → Global Constraints + 各任务 Files；§5.2 UV 平铺 → 任务3 Step 2；§7 spike → 任务1；§8 七闸 → 任务0'~7；§10 S4 重定义 → File Structure（两接口壳只登记不实现）+ 收口说明；§11 读码先决 → 任务0 Step 2~8 + 任务2/5/6/7 Step 1；§12 风险 N1~N8 → N1 任务1 Step 13，N2 任务3 Step 1/笔记 §10.5，N3 任务3 Step 5，N4 任务2 Step 3（投票参数常量），N5 任务3 Step 1（u16 容量），N6 任务5 Step 2，N7 任务2 Step 3（90% 阈值参数），N8 任务2 Step 8（驻留缓存裁决入口）。
- **Placeholder 扫描**：无 TBD/TODO；代码步均有代码块或精确不变量 + 命令 + 预期；所有"初值"项带明确数值 + 目验/实测出口。
- **类型一致性**：CellKey/CellMeshExtractor/CellGeometryObserver/BlockTileView/HeapSubregion/LodVote/CellBuildBudget/SpriteRectTable/L0ColumnTopIndex/CellOcclusionCuller 在任务0 定义（接口）与任务1~7 使用处名称一致；DHVK_* 开关族（DHVK_TEX/DHVK_MESH/DHVK_NOCULL/DHVK_MOUNTAIN/DHVK_VRS）与 S1 既有 DHVK_NOWALL/NOPROBE 同族命名。

## Execution Handoff

计划已存 `docs/superpowers/plans/2026-09-16-s2v2-greedy-mesh-pivot.md`。两种执行方式：
1. **Inline execution（executing-plans，本项目惯例）**——本会话内执行，每闸一个 checkpoint（目验裁决必须在机前，闸门节奏 = 栞启动 → 君盯屏）；
2. **Subagent-driven**——每任务新 subagent + 任务间两段评审（目验裁决仍由君在机前做）。
