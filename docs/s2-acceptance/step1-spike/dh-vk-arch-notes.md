# DH (Distant Horizons) 26.2/Blaze3D 渲染架构摘要 —— 对题 H3 的参照基准

> 来源: refs/dh-vulkan (Vulkan 分支 @ 64d8f7e, 3.0.4-b-dev, 26.2 profile) + refs/dh (main @ f5d2f80,
> 3.2.1-b-dev)。本摘要 = 栞 2026-09-17 亲读源码提取 (子代理精读失败后改人工), 只记与
> 本项目 H3 (run73-86 带几何直绘主目标不可见) 直接相关的机制, 代码引用见文末。
> 许可: LGPL-3.0 → 并入本项目 GPL-3 单向兼容 (README 结案时说明来源)。

## 1. 架构总纲 (Vulkan 分支, 26.x Blaze3D 后端)

DH **不用 FrameGraphBuilder pass, 也不建第二 CBU**:
- GpuDevice.createCommandEncoder() 在 26.2 返回**设备唯一共享 CBU** (VulkanDevice L182-183:
  构造器里 new 一个, createCommandEncoder 恒返回它)。官方帧图与 DH 全部渲染在**同一条命令流**上。
- DH 通过 mixin 钩子 (MixinLevelRenderer.renderLevel / prepareChunkRenders;
  MixinChunkSectionsToRender; FabricClientProxy) 在官方帧执行的**中途**触发
  ClientApi.renderLods() → 在共享 CBU 上**追加**自己的渲染实例; 帧末由设备统一提交,
  队列顺序天然把 DH pass 串进官方帧 (无需任何显式 semaphore/fence —— 全渲染层 grep 无
  submit/Semaphore/waitIdle)。
- DH 绘制目标是**自己的离屏 texture 对** (BlazeDhMetaRenderer: dhColorTextureWrapper +
  dhDepthTextureWrapper, 与主视口同尺寸, tryCreateOrResize), 用自己的
  COMMAND_ENCODER.createRenderPass(label, dhColorView, ..., dhDepthView, ...) 建渲染实例。
- 合成回 MC 场景 = **全屏三角扇 pass** (BlazeDhApplyRenderer: 源 = DH 颜色+深度 texture view,
  目的 = MC 主颜色 target view, dummy 深度, 深度测试 NONE/不写, 按"源深度有效"拷贝/混合),
  后接 fog/fade/TAA/SSAO 后处理 (BlazeDhFogRenderer/BlazeVanillaFadeRenderer/BlazeDhSsaoRenderer)。

## 2. 管线构建 (RenderPipelineBuilderWrapper.build → 官方 RenderPipeline.builder)

26.2 分支 (MC_VER > MC_26_1_2) 的映射:
- 深度: withDepthStencilState(new DepthStencilState(compareOp, writeDepth)) —— 与本项目同款;
- 颜色: new ColorTargetState(Optional.ofNullable(blend), WRITE_ALL/WRITE_NONE);
- **顶点: blazePipelineBuilder.withVertexFormat(vertexFormat, VertexFormat.Mode)** ——
  注意是 withVertexFormat(format, mode) 这个新 API, 且 vertexFormat = **自定义
  VertexFormat.builder() 构建** (DH 用 SHORT_XYZ_POS + META + RGBA_UBYTE_COLOR +
  iris 项 + BYTE_PAD 填充到 4 字节倍数), 不是 DefaultVertexFormat 常量。
  **本项目现在用的是 withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR) (旧形 API) ——
  对题差异 #1 (待 A/B 验证: 26.2 Vulkan 后端下 withVertexBinding 的 vertex-input 编译是否健全)。**
- UBO: 名字进 BindGroupLayout (withUniform(name, UNIFORM_BUFFER)) —— 与本项目 GEOMETRY BGL
  同族; 官方管线创建自带后端的描述符管理, **DH 全程无静态映射手术/无 push 取消/无堆表手写重写**。
- 采样器: withSampler(name) (≤26.1.2 直调; >26.1.2 收进 BGL)。

## 3. 每帧数据与绑定 (BlazeDhTerrainRenderer.render)

- UBO: 每帧 Std140Builder 填 CPU 缓冲 → BlazeUniformUtil.createBuffer(name, size, 旧缓冲)
  (尺寸不变则复用 GpuBuffer) → 共享 CBU 的 writeToBuffer(slice, cpu) →
  **renderPass.setUniform(name, GpuBuffer) 整缓冲绑定** (官方 RenderPass 有
  setUniform(String, GpuBuffer) 与 setUniform(String, GpuBufferSlice) 两个重载, 官方都有)。
- VBO: 每个 LOD 一个独立 GpuBuffer (构建期填充), **setVertexBuffer(0, 整 GpuBuffer)**,
  无环切片、无偏移、无保留窗。
- IBO: **setIndexBuffer(整 GpuBuffer, IndexType.INT)** + drawIndexed(indexStart=0,
  firstIndex=0, indexCount, 1) (官方 26.2 drawIndexed 为 5 参
  (indexCount, instanceCount, firstIndex, vertexOffset, firstInstance), 与本项目签名一致;
  DH 包装器的 4 参形式映射到同一调用)。
- 顶点格式与索引宽: u32 索引 (IndexType.INT) 在 20 万级顶点下是 DH 生产配置 →
  本项目的 u32 IBO 选择与 DH 同, 排除"索引宽"嫌疑 (VVL 亦从未报不匹配)。

## 4. 与本项目 (H3) 的逐条对照 —— 差异即嫌疑排序

| # | 机制 | DH (可见) | 本项目 (不可见) | 嫌疑权重 |
|---|---|---|---|---|
| 1 | 绘制目标 | **离屏 texture 对 + 全屏扇合成** | 直绘主 target (帧图 pass readsAndWrites(main)) | 高 (深度/附件/帧序全隔离) |
| 2 | 顶点输入 API | **withVertexFormat(自定义format, mode)** | withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR) | 高 (26.2 新后端下旧形 API 的编译态未经验证) |
| 3 | UBO 通道 | **纯官方 setUniform(GpuBuffer), 零手术** | 幻影 VBO/IBO 块 + 静态映射手术 + 堆表逐帧重写 + push 取消 | 中 (run85 实证: 手术窗外编译即 classic-vs-heap 崩) |
| 4 | VBO 绑定 | 整 GpuBuffer, offset 0 | 环 slice 偏移 (run82 后已换持久整缓冲 @ offset 0) | 低 (已对齐) |
| 5 | 帧序 | mixin 钩进官方帧中途, 共享 CBU | 帧图 pass (也是共享 CBU, 但位置=帧图排定) | 低 |
| 6 | 深度 | 离屏深度自持; 合成扇 NONE | 主深度 DEFAULT (LESS) | 并入 #1 |

已排除 (run73-86 证据): 顶点格式 16B 与数据同形、描述符地址/对齐 (run30/run85 审计)、
环区被覆写 (run82 持久缓冲后仍不可见)、canary 位移 (run84 解耦后仍不可见)、
IBO/VBO 槽内容 (run84 探针自报: VBO 内容真)。

## 5. 迁移方案 (借鉴 DH, 分两刀)

**刀一 (最小炉 = run87, DHVK_NOSURGERY): 关手术链, 纯官方描述符通道**
- 勘误: 顶点输入 API 无需动 —— 26.2 正式版 RenderPipeline.Builder 只有
  withVertexBinding(int, VertexFormat) (mc-src 实证 L260), 本项目用法即官方终形;
  DH 包装器的 withVertexFormat(format, Mode) 是 26.2-snapshot-5 时代 API (其 profile 即
  minecraft_version=26.2-snapshot-5), 正式版已被 withVertexBinding 取代 → 嫌疑 #2 撤销。
- 故刀一聚焦嫌疑 #3: DHVK_NOSURGERY=1 关静态映射/名册/堆表重写, UBO 全走官方
  setUniform 原生通道 (DH 全程零手术)。可见 = 手术链即元凶 (run85 旁证); 不可见 → 进刀二。

**刀二 (若刀一无效, 完整 DH 形态): 离屏 + 合成扇**
- 建自己的颜色+深度 texture 对 (照 BlazeDhMetaRenderer: 与主视口同尺寸, 每帧
  tryCreateOrResize);
- 带几何画进离屏对 (自己的 createRenderPass, 共享 CBU, 帧图 pass 内或钩子内均可 ——
  共享 CBU 使帧序自动正确);
- 全屏扇 pass 把离屏颜色按深度合成回主 target (照 BlazeDhApplyRenderer 的 shader 逻辑:
  源深度有效才拷贝, 供后续雾/淡出使用);
- 合成扇 = 新的极简探针 (三角形级, 先证扇可见, 再挂 DH 纹理)。

**保留的 DH 习惯 (写进我们的远 pass 规约)**: 整缓冲绑定 (不玩环切片)、每帧 UBO =
复用的 GpuBuffer + writeToBuffer、管线一次 build 全程复用、render pass 用 try-with-resources。

## 6. 关键代码索引 (refs/dh-vulkan)

- common/.../render/blaze/BlazeDhTerrainRenderer.java —— 远地形渲染器全链 (管线/UBO/绑定/draw)
- common/.../render/blaze/BlazeDhMetaRenderer.java —— 离屏 texture 对生命周期
- common/.../render/blaze/apply/BlazeDhApplyRenderer.java —— 合成扇管线与调用
- common/.../render/blaze/wrappers/RenderPipelineBuilderWrapper.java —— 官方 builder 映射 (26.2 分支见 build())
- common/.../render/blaze/wrappers/uniform/BlazeUniformUtil.java —— UBO 创建/复用/writeToBuffer
- common/.../render/blaze/test/BlazeDhTestTriangleRenderer.java —— 最小可画像素模板
- fabric/.../mixins/client/MixinLevelRenderer.java (L226-250 26.x 段) + MixinChunkSectionsToRender +
  FabricClientProxy (L247 renderLods) —— 帧钩子
- 官方侧 (mc-src): VulkanDevice.java L182-183 (共享 CBU 单例); CommandEncoder.java L36-37
  (submit = backend.submit); RenderPass.java L103-157 (setUniform 双重载/setVertexBuffer slice/
  setIndexBuffer buffer+type/drawIndexed 5 参); FrameGraphBuilder.java (pass 执行也在共享 CBU)。
