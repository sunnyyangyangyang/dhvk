# 移植方案: 远 pass 整套换 DH 渲染方法 (几何保留我们的体素带)

> 2026-09-17 君拍板: "要不完全使用dh的渲染方法(当然几何还是我们的那个体素版本)"。
> DH 本体实测 (test5, 纯 classic 设备零手术) 已在本世界画出远地形 = 该架构在此栈上活体可用。

## 目标架构 (run89 起, 照 DH 26.2 姿势 1:1)

1. **设备层**: 手术默认关闭 (DHVK_SURGERY=1 可回开做 A/B) → 原生 vanilla 设备,
   全部管线 classic 自洽 (test5 实证: 零手术 + 官方经典通道 VVL 安静、远地形可见)。
2. **帧钩子**: 弃用 FrameGraphBuilder pass; 改 @Inject TAIL of LevelRenderer.renderLevel
   (官方场景帧图执行完之后, 在设备共享 CBU 上追加两个渲染实例; 帧末设备统一提交 —
   DH 同款"同一条命令流中途追加"节奏, 其官方 CBU 单例已实证: VulkanDevice L182-183)。
3. **离屏目标对** (新类, 照 BlazeTextureWrapper 26.2 分支的官方调用):
   - GPU_DEVICE.createTexture(name, USAGE_COPY_DST|TEXTURE_BINDING|COPY_SRC|RENDER_ATTACHMENT,
     GpuFormat.RGBA8_UNORM | GpuFormat.D32_FLOAT, viewportW/H, 1, 1)
   - GPU_DEVICE.createTextureView(tex); 每帧 tryCreateOrResize (视口变才重建, 旧纹理 close)
   - 采样器 GPU_DEVICE.createSampler(CLAMP_TO_EDGE×2, LINEAR×2, 1, OptionalDouble.empty())
   - 清屏: 共享 CBU 的 clearColorTexture(tex, argb) / clearDepthTexture(tex, 1.0f)
4. **带几何 → 离屏对**: 官方 createRenderPass(label, colorView, clearColor, depthView, depth1.0)
   上画我们的体素带 (buildMeshBand 产物原样: 16B pos+color 交错, u32 索引, 持久整 GpuBuffer):
   - 管线 = 纯官方 builder: withVertexBinding(0, 16B format) + withPrimitiveTopology(TRIANGLES)
     + DepthStencilState(LESS, write) + ColorTargetState(WRITE_ALL) + BGL 具名 UBO
     (Projection/DynamicTransforms/Fog/Globals — 幻影 VBO/IBO 块整体退役)
   - 绑定 = setUniform(名, 整 GpuBuffer) (每帧缓冲区: 尺寸不变复用, 变则重建,
     共享 CBU writeToBuffer 上传 — BlazeUniformUtil 模式)
   - setVertexBuffer(0, 整 GpuBuffer); setIndexBuffer(整 GpuBuffer, IndexType.INT);
     drawIndexed(indexCount, 1, 0, 0, 0)
5. **合成扇 → 主目标** (新 shader, 逻辑抄 DH apply/gl/apply.frag):
   - 全屏 NDC 四角 TRIANGLE_FAN; 管线 depth NONE + 不混合 + BGL 两个 sampler
     (uSourceColorTexture / uSourceDepthTexture)
   - frag: 源深度 == 1.0 (未画到, 非反Z) → discard; 否则 fragColor = 源颜色
   - 目标 = 官方主目标颜色 view (MC_RENDER 主纹理, 官方 26.2 API 待查: MainTarget/MinecraftRender)
6. **退役清单** (代码保留、门控关断): 设备手术 mixin (默认关) / 管线静态映射 / push 名册 /
   堆表逐帧重写 / BDA usage 与哨兵缓冲 / 环切片 (run82 起已不用)。
7. **探针**: 保留极简形态 — 离屏对上的 ALWAYS_PASS 小扇 (可选 DHVK_NOPROBE 门控), 或先退役。

## 实施切片 (每片可独立点火)
- S1: 设备手术默认关 + 探针退役 → 验证纯 classic 设备下官方帧无恙 (我们的 pass 仍暂走帧图, 只换管线绑定为整缓冲+纯setUniform) — 其实 test5 已替我们验证了设备层, S1 可与 S2 合并。
- S2: 离屏目标对 + TAIL 钩子 + 带几何画进离屏 (先不做合成扇, 探针扇画在离屏上验证管线)
- S3: 合成扇上主目标 → 纸片/山影在主场景现身 (本移植的验收点 = 君的地平线截图)
- S4: 收尾: 删死代码、README 关系说明 (LGPL-3 DH 来源: main@f5d2f80 / Vulkan@64d8f7e,
  core@64c5d96/269f2c3)、rc-budget 按 16B 体素布局复核。

## 参考代码索引 (refs/dh, main @ f5d2f80)
- 离屏对生命周期: common/.../blaze/wrappers/texture/BlazeTextureWrapper.java (createTexture 调用见上)
- 离屏对使用: BlazeDhTerrainRenderer.java (createRenderPass 到 dh 颜色/深度 view)
- 合成扇: apply/BlazeDhApplyRenderer.java + coreSubProjects/core/.../shaders/shared/gl/quad_apply.vert
  + shaders/apply/gl/apply.frag (discard 逻辑见上; 26.2 非反Z → drawnTo = depth != 1.0)
- 帧钩子: fabric/mixins/client/MixinLevelRenderer.java (renderLevel HEAD 填 RENDER_STATE;
  MixinChunkSectionsToRender.prepareChunkRenders 触发 renderLods) + MixinLevelRenderer 尾段
- 缓冲复用/上传: util/BlazeUniformUtil (createBuffer(name,size,old) + writeToBuffer)
