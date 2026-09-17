# S2v2 任务 1（贪心 spike）验收：far band 贪心几何首通

**状态：✅ 任务 1 过闸**（步骤 12 目验三项目全过 + 步骤 13 R-c 定死 + 最终代码形态 VVL 双零：标准 run65/66 + 全开 run67）；余 = 步骤 14 定稿 commit + 步骤 15 高度场退役（独立 commit）
**日期：2026-09-16 深夜 | 机器：RTX 5090 (driver 615.71.9.0) | MC 26.2 + Vulkan | VVL 1.4.341**

## 验收物（计划 Task 1 门）

1. **贪心几何首通**：CPU 二进制贪心 mesher（笔记 §10.7 参照 cgerikj 移植）产出真实地形
   几何 → 官方瞬态 ring 流式 → 官方相机（继承 S1 管线）出图。
2. **VVL 双零**：标准档 + 全开档（VK_LAYER_SETTINGS_PATH=mc-src/scripts/vvl-fullprofile）
   零验证行。
3. **遥测**：far pass GPU 实耗 + 逐 tile 顶点/三角形计数（R-c 实测数据源）。
4. **目验三项目**（用户裁决）：崖壁可读 / 树木成团 / 洞口可见。
5. **干净退出**。

## run 序列（run49 起 = 贪心接线后）

| run | 档 | 事件 |
|---|---|---|
| run49 (20:47) | 标准 | 贪心首跑：品红/蓝巨面片糊屏（用户截图点名）；band 全空气（verts=1）|
| run50 | 标准 | 三修（firstIndex ÷ / r256 门槛 / 近场豁免 64 块）后首跑：仍全空气 + frame2883 尖峰 1482µs |
| run51 | 标准 | FULL 语义修复（isLoaded 只查区块存在不查 status）：r256 探针帧 57 通过仍全空气（玩家在半空 y=156 触发门槛，带被抬进天空）|
| run52 | 标准 | Y 带锚地形表面（WORLD_SURFACE 高度图）：几何首出（842/642/1616 quads）→ BlockTintCache NPE（自造 lambda resolver 查 tintCaches 落空）|
| run53 | 标准 | 官方 BiomeColors 常量 resolver：**band 全量首通** 74773 顶点 / VBO 1.17MB / IBO 292KB；**VVL 标准零**；farpass 28~50µs；用户截图 = 白崖壁+绿树线 |
| run54 | 全开档 | 同步+GPU协助+最佳实践：**VVL 全开零**；farpass 14~21µs；干净退出 |
| run55 | 标准 | 复验（用户三角度截图）：**VVL 标准零**；band 71893 顶点（落点偏移 origin=(96,0,0)）；frame436 孤立 stream 尖峰 7619µs（S1 笔记 1268-1270 备案形态放大版，闸门只报不兜底）；干净退出 |
| run56 | 标准 | 用户中途关游戏（关时 VVL 标准零；日志留前会话私有 tmp，由 run57 接替）|
| run57 (21:25) | 标准 | 用户目验局：band 62149 顶点 / VBO 994400B / IBO 248616B，bandY=[0,96]；**VVL 标准零**、无流突发；farpass 6~61µs；用户截图×2（白色坡壁轮廓/峡谷双壁）判"遮挡没问题"，干净退出 |
| run58 | 标准 | 门槛中心修复前首启，编译修复后弃用（killed，未见帧）|
| run59 (21:34) | 标准·钉带 | 门槛绕带中心修复后首跑：玩家 /tp 洞口处（playerY=122）带即建（146ms，waitFrames=87），51799 顶点（玩家带内→近场豁免生效）；**VVL 标准零**，干净退出 |
| run60 (21:37) | 标准·钉带 | **R-c 数据源**：玩家 /tp (1020,130,860)（距带 200+ 块，纯远带视野）；band 69175 顶点 / VBO 1106816B / IBO 276720B；全带 quads=45366（最坏单 tile 3992@(25,2,27)）；**VVL 标准零**、无流突发、farpass 21~29µs；用户现场确认洞口后干净退出 |
| run61 (21:39) | 标准·钉带 | 复看局：用户早关，门槛未及放行（band 未建），VVL 标准零，干净退出 |
| run62 (21:43) | 标准 | 用户 /tp (1020,130,860)：band 建于东侧丘陵，quads=28452；崩溃定谳：秒表查询池 CPU 侧重置撞两帧在途 → 驱动竞态 → present Failed（VVL 行 5 条）|
| run63 (21:47) | 标准 | 重置移 CBU 侧首试 → VVL 实锤禁于 render pass 实例内 → 重置移 createRenderPass 前（pass 外同 CBU）|
| run64 (21:50) | 标准 | wrapper 层接口注入缺实现 → 首帧 AbstractMethodError → CommandEncoderDhvkProbeMixin 补委托 |
| run65 (21:51) | 标准 | 三连修后首通：VVL 标准零 + vkCmd 零 + ABM 零；band 建于前位（992,832），平原未入带（用户：没看到新东西 = 站在带内，近 pass 盖住远带，符合架构预期）；干净退出 |
| run66 (21:53) | 标准·钉带 | 平原 R-c 数据源：钉 (1659,320)，bandY=[0,96]，quads=33771 / verts=202627 / VBO 3242048B / IBO 810528B（玩家空中豁免未触发 kept≡quads）；VVL 标准零 + vkCmd 零；干净退出 |
| run67 (21:55) | 全开档 | 三连修后全开档复验：**VVL 全开零 + vkCmd 零**；band 平原带 195511 顶点；farpass 7µs；干净退出。双零（最终代码）= 标准 run65/66 + 全开 run67 |
| run62 (21:43) | 标准 | 用户 /tp (1020,130,860)：band 随玩家建于东侧丘陵（origin=(992,64,832)），quads=28452；**run62 崩溃定谳**：秒表查询池 CPU 侧重置（官方 writeTimestamp 内建）撞上两帧在途 → 驱动侧竞态 → vkQueuePresentKHR "Failed to present image"（VVL 行 5 条）|
| run63 (21:47) | 标准 | 查询重置移 CBU 侧首试 → VVL 实锤 `vkCmdResetQueryPool 禁于 render pass 实例内`（VUID）→ 修：重置移 createRenderPass 之前（pass 外、同 CBU）|
| run64 (21:50) | 标准 | wrapper 层接口注入缺实现 → 首帧 AbstractMethodError（26.2 createCommandEncoder 返回 systems.CommandEncoder wrapper，backend 私有）→ 修：CommandEncoderDhvkProbeMixin 补委托 |
| run65 (21:51) | 标准 | **三连修后首通**：用户 /tp 平原 (1659,161,320)；VVL 标准零 + vkCmd 行零 + ABM 零；band 仍建于前位（992,832，随玩家早期位置），平原视野未入带（用户："没看到新东西" = 站在带内/带缘，近 pass 纹理地形盖住远带，符合架构预期）；干净退出 |
| run66 (21:53) | 标准·钉带 | **平原 R-c 数据源**：钉 (1659,320)，bandY=[0,96]（surfaceY=68），quads=33771 / verts=202627 / VBO 3242048B / IBO 810528B（玩家空中→豁免未触发，kept≡quads 最严形态）；**VVL 标准零 + vkCmd 行零**；干净退出 |
| run67 | 全开档 | 三连修后全开档（vvl-fullprofile）复验：见下行补记（待 run67 收档）|

日志：/tmp/s2v2-run-run5{0,1,2,3}-*.log / s2v2-run-run54-full-*.log / s2v2-run-run55-210300.log
（原始 .log 按仓库契约留私有工作区，.gitignore 不收）

## 根因销账（五连修，全部定谳并修复）

1. **firstIndex 乘÷颠倒**：官方 vkCmdBindIndexBuffer 恒绑缓冲基址 0（VulkanRenderPass.java:187），
   firstIndex = slice 偏移字节 ÷ 索引元素宽；原代码 ×  → 从 ring 缓冲 offset 4096B 读垃圾
   索引 = 品红 head 顶点 + 垃圾顶点 → 糊屏巨片。
2. **26.2 isLoaded 语义**：Level.java:721 isLoaded = 区块存在（任意 status）；未完成区块
   getBlockState 返回空气 → 门槛与 BlockSource 的 hasData/实心判定全部改按
   ChunkStatus.FULL（getChunk(sx,sz,FULL,false) != null，vanilla loadedAnd* 同型）+ memo。
3. **Y 带锚玩家肉身**：出生下落中穿过 y∈[144,176) 即触发门槛，带被抬进半空（surfaceY=63
   实锤）→ 改锚 WORLD_SURFACE 高度图（run52 起）。
4. **tint resolver**：ClientLevel 26.2 tintCaches 以官方 ColorResolver 常量为键；自造 lambda
   查表落空 → getBlockTint NPE → 换 BiomeColors.{GRASS,DRY_FOLIAGE,FOLIAGE}_COLOR_RESOLVER
   （顺带复用 vanilla calculateBlockTint 完整染色）。
5. **近场豁免**：远带共享官方相机，玩家近处几何投成糊屏巨面 → 角点全在相机外 64 块的
   quad 才入远带（近 pass 纹理地形覆盖近区，无视觉洞）；探针诊断墙 DHVK_NOPROBE=1 关。

## 遥测（run53/54/55）

- **band 构建**：一次性 134~139ms（27 tile，含 getChunk FULL 判定 memo）；
  顶点 7.2~7.5 万（雪原山地，1024×1024×96 带）；R-c 预算 = 步骤 13 四类 chunk 实测最坏 × 1.5。
- **far pass GPU 实耗**：12~50µs（预算 1000µs，富余 20~80 倍）。
- **stream 上传**：每帧 ~30-80µs（1.17MB AoS 走官方瞬态 ring）；孤立尖峰形态备案
  （run50 1482µs / run55 7619µs@frame436 = S1 笔记 1268-1270 形态）。
- **VVL**：标准零（run53/55）+ 全开零（run54）= 双零达成。

## 已知形态（spike 期备案，不判失败）

1. **带顶/带底截断面**：带 Y 范围 [surface-48, surface+48] 切出的顶/底平切面（崖壁高出
   当地地表的部分 → 白板伸进星空）；XZ 带缘（1024² 边界）同理。任务 7 联合校准时重裁。
2. **平色无纹理**：spike 期 tint 直乘（雪=白/草叶=绿），图集纹理 = 任务 3。
3. **绿点粒度**：单 tile 贪心合并 + 无 LOD，远景树冠 = 离散绿点（任务 2 LOD 阶梯细化）。
4. **stream 孤立尖峰**：官方瞬态 ring 一次性成本/偶发渲染线程颠簸（S1 已备案形态）。
5. **stream 预算线（1000us，S1 时代常数）对 4MB 级带偏紧**：run66 平原带（VBO 3.1MB+IBO 0.78MB）标准档 122 帧越线 / run67 全开档 36 帧越线（全开档 VVL CPU 校验开销叠加）——语义 = 只报不兜底（ERROR 日志，不失效、不重传）；非 VVL 验证行、非崩溃。任务 2/3 带宽扩档时随遥测重校。
6. **秒表查询重置三连修（run62-67 定谳）**：官方 CPU 侧 vkResetQueryPool 撞两帧在途 → 驱动竞态崩溃（run62）；CBU 侧重置禁于 render pass 实例内（run63 VVL 实锤）；26.2 接口注入在 wrapper+backend 双层、wrapper 层缺实现 → AbstractMethodError（run64）。终态 = 重置于 createRenderPass 之前（pass 外同 CBU）+ 在途帧各用独立查询对（帧奇偶 x2）+ wrapper 层委托补齐。

## 步骤 12 目验判词（2026-09-16 深夜，全过 = 过闸）

裁决方式：用户现场截图 + 口头判词（三项目先后点名后宣布"开始下一步"）。

1. **崖壁可读 — 过**：run57 截图：屏幕中央白色坡壁轮廓与远近层次立得住；峡谷视角双壁立起
   （左壁横向条带 = 岩层贪心合并面平色形态，已知形态备案 2）；run49 糊屏巨面经用户复判
   "遮挡没问题"彻底退场。
2. **树木成团 — 过**：run53~57 截图：远带坡面绿点带/斜向树线；786/837 处 F3 截图
   （左坡成行树）补证中远景树团。
3. **洞口可见 — 过**：run60 钉带 (786,837)，bandY=[64,160] 整罩红石矿崖与洞口；用户 /tp
   (1020,130,860)（离崖 200+ 块，纯远带视野）现场确认，随即宣布进入步骤 13。

## 步骤 13：R-c 预算实测（四地形钉带）

方法：`DHVK_MESH_AT="x z"` 钉带；门槛探针改绕**有效带中心**（钉带自同步：玩家在出生点时
门槛自动顺延，玩家到位即放行）；近场豁免改**跟随相机**（玩家真实位置）——钉带模式带钉死、
玩家可在任意处，按带中心豁免会在带中心捅 64 块半径隐形洞（run59/60 中央 2×2 tile kept
塌方实锤；常规模式带中心≈玩家所在 tile，行为不变）。

| 地形 | 坐标 | run | quads 合计（无豁免上界） | 最坏单 tile quads |
|---|---|---|---|---|
| 高山+森林+洞口（同址复合，海拔 122） | 786 837 | run60 | **45366**（全局最坏） | 3992（tile 25,2,27） |
| 丘陵（洞口东侧） | 1020 860 | run62 | 28452 | 2554（tile 31,3,26） |
| 平坦（平原） | 1659 320 | run66 | 33771 | 3777（tile 50,2,11） |

- **R-c 定死**（详 `rc-budget.md`）：全局最坏 45366 quads = 272197 顶点 → VBO 4.35MB / IBO 1.04MB；
  ×1.5 → 单帧 8.1MB，3 帧保留窗驻留 ≈ 24.7MB < 31.6MiB 几何池（32MiB − 表 256KB − 驱动预留 96KB），
  裕量 22% → 堆 init 常数无需增长；10km 多带留 Task 7 联合标定。

## 待办（步骤 12-15）

- [x] 步骤 12：用户三项目验判词（崖壁/树线/洞口）+ 截图归档 → **全过 = 过闸**（2026-09-16 深夜判词）
- [x] 步骤 13：四类 chunk（高山/森林/洞口同址 786 837 + 丘陵 1020 860 + 平原 1659 320）
      DHVK_MESH_AT 钉带实测 → R-c = 45366 quads × 1.5 定死（rc-budget.md）
- [x] 步骤 14：本文件定稿 + commit（rc-budget.md 同批）
- [ ] 步骤 15：高度场（S1 合成 ring）退役（独立 commit + VVL 标准烟测）
