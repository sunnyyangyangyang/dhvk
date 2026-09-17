# run108 后 SEGV 调试状态 (更新至 2026-09-17 00:55, 压缩锚点)
## 已定罪
- run109 对照: 静音 dhvk(空 entrypoints)+同 vmArgs+同 VVL+同世界 → 100s 无 SEGV, vanilla 干净 ⇒ 崩溃在 mod 与环境的交互。
- 五连SEGV(run96/100/101/102/103)+run108 同桩: StubRoutines::jbyte_disjoint_arraycopy, si_addr=0x10(=null byte[] 的长度字段偏移), 栈恒带 VulkanTransientMemory + GpuBufferSlice$MappedView::slice; 所有 run 均 --vulkanValidation。
- CompileCommand 踢 C2 (run108) 无效 ⇒ 非纯 JIT bug = 真实引用损坏/竞争。coredumpctl 已存 7 个 core(442M, /var/lib/systemd/coredump/)。
## 当前代码
- 帧函数重构: dhStyleFrame = NOBAND?跳过:renderBandPass(encoder) + NOFAN?return:renderFanPass(encoder,mainTarget)。
  - renderBandPass: offscreen.clear + 借官方DT slice(UNIFORM_SLICES, null则return) + PIPELINE_DH 带 pass。
  - renderFanPass: 绑离屏 color/depth view + 扇 VBO, PIPELINE_APPLY 写 mainTarget.getColorTextureView()。
- HEAD hook: ensureBuffers + meshFrame(几何+createBuffer(ByteBuffer)上传) + offscreen.tryCreateOrResize + createFanVbo; TAIL: dhStyleFrame(上)。
- vmArgs: -XX:CompileCommand=exclude,MemoryUtil.memCopy (build.gradle, 保留观察)。
- 信标 DHVK_BEACON=1: 静态红碑 x[1571,1747] z=248 y[40,320]; /tp 1648 130 336 朝北(-z)。
## 下一步(进行中)
- 二分: NOFAN=1(只带) / NOBAND=1(只扇) 各 timeout 100s 自动杀; 对照=静音vanilla干净/全开=炸(run108)。
  - NOFAN 干净 → 扇 pass(主目标外写)是凶手; NOBAND 干净 → 带 pass/离屏清屏是凶手; 都炸 → 公共路径(HEAD staging/离屏创建)。
- 嫌疑排序: ① 扇 pass 对 mainTarget 的 color-only 外写(布局/帧图所有权); ② 帧首 HEAD 对官方瞬态 ring 的 staging 污染记账; ③ offscreen 清屏+离屏对。
## 验收链
- VVL 双零 + 君地平线截图(碑) → 真实带(DHVK_MESH_AT=1659 320 LIFT=40) → S4(清理+README LGPL-3+推 run87→run103+ 段提交)。
