# S1 读码先决笔记（任务 0 产出；后续任务的事实来源）

- 日期：2026-09-14（随任务 0 执行增量补全；与代码冲突时以本笔记为准）
- 依据：`mc-src/client/` 26.2 反混淆树（源码真相）+ `mc-src/artifacts/client.jar`（官方 shader/GLSL）+ `refs/vulkan-headers`（1.4.357）+ 宿主 glslang 16.2 / spirv-tools 2026.1
- 状态：§1（含 1.6）/§2/§4/§5/§7 已有实施决定；§3/§6 的驱动语义留给任务 2 的 VVL 裁决（设计判据已定）

## §1 顶点输入 ABI 与堆源 BGL 声明（任务 2/3 用）

**已核实事实：**
1. 官方 `terrain.vsh`（client.jar 提取，769B 全文已读）：`in vec3 Position; in vec4 Color; in vec2 UV0; in ivec2 UV2;`——**无 location 的经典 `OpVariable Input`**，靠名字/声明顺序与管线顶点输入状态自动接线（游戏运行时编译器处理 `#moj_import <minecraft:*.glsl>`；宿主 glslang 离线复现需：内联 include + 块补假 `binding=` + 升 `#version 450` + `--auto-map-locations` + **`.vert` 后缀**）。
2. S0 顶点着色器反汇编实证（glslang 16.2 → spirv-dis）：`%Position = OpVariable %_ptr_Input_v3float Input` / `%Color = ... Input`；uniform 块 = 独立具名 std140 块（%DynamicTransforms / %Projection / %Fog / %ChunkSection），**按块名匹配 set layout**（`bindDefaultUniforms` 机制）。
3. **per-draw 官方通道（S1 per-cell 模型照抄）**：`chunksection.glsl` = `layout(std140) uniform ChunkSection { mat4 ModelViewMat; float ChunkVisibility; ivec2 TextureSize; ivec3 ChunkPosition; }`；官方 vsh 公式 `pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset; gl_Position = ProjMat * ModelViewMat * vec4(pos,1)`。
   → **S1 实施决定（推翻 spec 的"mat4 平移"措辞，取官方同款 int 平移）**：per-cell 数据经官方 `writeChunkSections` 下发，其中 `ModelViewMat` = 相机 modelview（与全体 chunk 相同），`ChunkVisibility` = 淡入值，`TextureSize` = (1,1) 哑元，`ChunkPosition` = **cell 世界原点（ivec3，整数平移，精确无 float32 抖动）**；顶点 `Position` = cell 相对坐标（块，0..32 / y 相对基线）。shader 公式与官方 terrain.vsh 逐字同构。
4. `BindGroupLayout` = 公共 builder 数据类（`net.minecraft/client/renderer/BindGroupLayouts.java`）：`BindGroupLayout.builder().withUniform(name, UniformType).withSampler(name).build()`；现成常量含 **`CHUNK_SECTION`**（uniform "ChunkSection" UNIFORM_BUFFER）——**S1 的 per-cell 通道直接用官方 `BindGroupLayouts.CHUNK_SECTION`**（pipeline BGL 集合 = GLOBALS + MATRICES_PROJECTION + FOG + CHUNK_SECTION）。
5. `VulkanRenderPipeline.compile(device, VulkanBindGroupLayout layout, RenderPipeline, vsm, fsm)`（`vulkan/VulkanRenderPipeline.java` 已全文读）：
   - pipeline layout = **单个** `VkDescriptorSetLayout`（`layout.handle()`，设备把 pipeline 的全部 BGL 合并成一个 set layout 缓存为 `VulkanBindGroupLayout`）；`VkPipelineLayoutCreateInfo` 无 pNext 链；
   - `VkGraphicsPipelineCreateInfo` 的 `pNext(renderingInfo)`（L197 附近，`renderingInfo = VkPipelineRenderingCreateInfoKHR`，depthAttachmentFormat=126 固定）——**唯一 pNext 注入点**；
   - 顶点输入状态：经典 `VkPipelineVertexInputStateCreateInfo`（属性 location 按声明顺序递增、binding stride 来自 VertexFormat）+ 可选 `VkPipelineVertexInputDivisorStateCreateInfoEXT` pNext。
   - → **顶点输入 ABI 形状裁决：经典 OpVariable Input + 输入装配重定向（假设 A 成立）**：堆源 VBO/IBO 描述符只是换"IA 从哪里读地址"，顶点输入状态与 shader 的 `in` 声明不变（与 1.4.357 的 mapping source 机制自洽：描述符 payload 放堆里，GPU 读堆拿地址，IA 状态机不动）。
6. **几何 BGL 声明——1.6 自定义 BGL 入口（已钉死，任务 2 实施依据）**：
    - **设备侧没有 BGL 缓存**：`GpuDevice.java`（`systems/` 包）对 BindGroupLayout **零引用**；`VulkanBindGroupLayout` 是 `record(long handle, List<Entry>)`（+ `INVALID_LAYOUT` 常量），**每次 pipeline 编译时由 `GlslCompiler.compile()` 现场创建、随 `VulkanRenderPipeline` 一起被 `VulkanDevice.pipelineCache`（IdentityHashMap，key = RenderPipeline 对象标识）缓存**——没有"先注册 BGL 再取"的两段式，BGL 是 shader 编译的**派生物**。
    - **entry 派生规则（`GlslCompiler.addToBindGroup`，已全文读）**：SPIR-V 反射（spvc）出的 `uniformBuffers()` → 名字必须在 `pipeline.getBindGroupLayouts()` 的 UniformDescription 里（否则 ShaderCompileException）→ entry 类型 **UNIFORM_BUFFER（descriptorType 6）**；`samplers()` → 名字在 flattenUniforms 且 dim==5（SpvDimBuffer）→ **TEXEL_BUFFER（4）**，名字在 flattenSamplers 且 dim∈{1,3} → **SAMPLED_IMAGE（1）**。**Entry 类型枚举只有这三种**，set layout 创建时 binding i = entry 顺序，stageFlags 恒 17（VERTEX|FRAGMENT），layout flags=1。
    - **`rebind(vertexInputNames, entries)`（已全文读）= SPIR-V 就地改写**：shader 的 Input 变量 **按名匹配** vertex format 元素名，Location 装饰改为按序递增整数（匹配不到的 Input → 抛"expects input variables which are not being provided"）；UBO 的 binding 装饰改为 entry 索引（shader 里有而 entry 没覆盖的 UBO → 抛"uniform buffers which are not being provided"）——**shader 里每个具名资源都必须被 pipeline BGL 声明覆盖**。
    - **自定义入口 = `GpuDevice.precompilePipeline(pipeline, customShaderSource)`（public，`VulkanDevice` L257 实现）+ S0 已验证的 `RenderPipeline.builder()` 与 dhvk dev-pack ShaderSource 组合**（S0 双墙管线即走此路，无新增机制需求）。
    - **→ 任务 2 实施决定**：VBO/IBO 堆源描述符的 **phantom binding 在 shader 里声明为 UBO 形块**（如 `layout(set=0, binding=X) uniform VBO { ... } vbo[];`，只被驱动取地址、shader 不解引用），名字登记进 pipeline BGL 描述 → 自动落 UNIFORM_BUFFER(6) 进 set layout；2 代 mapping source（HEAP_WITH_CONSTANT_OFFSET）是**命令级**声明，不改变 set layout 的 1 代类型。风险位（任务 2 VVL 裁决）：若 VVL 判"1 代 UNIFORM_BUFFER 绑定 + 堆源"不合法 → 换 2 代 SPIR-V 描述符类型，届时 spvc 反射分类可能既非 uniformBuffer 也非 sampler → `addToBindGroup` 漏派生 → 需对 `GlslCompiler` 做最小 mixin 手术补 entry；裁决以 VVL 双零为准。

## §2 encoder raw CBU（任务 2 的绑定发射点）

**已核实**：`VulkanCommandEncoder`（`vulkan/VulkanCommandEncoder.java`）持有 `private @Nullable VkCommandBuffer currentCommandBuffer;`（L59）+ `allocateAndBeginTransientCommandBuffer()`（L113，从 `currentCommandPool()` 分配）。官方 pass 录制走 encoder 的 CBU。
**实施决定**：新增小探针 `VulkanCommandEncoderProbeMixin`——`@Shadow` 该字段 + 公共访问器 `long dhvkCurrentCbu()`（返回 `currentCommandBuffer != null ? currentCommandBuffer.address() : 0L`）；`FarTerrainRenderer.render()` 在 `setPipeline` 之后、`drawIndexed` 之前调用 `heap.bind(dhvkCurrentCbu(), 0, 整表)`（同一 CBU 上顺序发射，VVL 核验时机点）。

## §3 堆内存写入路径 + 5090 memory type（任务 2 实施；驱动语义由 VVL 裁决）

- API 面（1.4.357 + LWJGL 3.4.1 类面已核实）：`vkWriteResourceDescriptorsEXT(device, count, VkResourceDescriptorInfoEXT*, VkHostAddressRangeEXT*)`；buffer 描述符 payload = `VkResourceDescriptorDataEXT.pAddressRange`（`VkDeviceAddressRangeEXT` 纯地址区间）；GPU 读位 = `VK_ACCESS_2_RESOURCE_HEAP_READ_BIT_EXT`。
- vk.xml 新格式只剩签名无 prose → 两个候选写入路径的任务 2 裁决法：**先试 A = host-visible 映射直写**（`vkMapMemory` 拿 CPU 窗口，按 driver 描述符编码写入——编码格式若 driver 私有则 VVL 报错即换 B）；**B = `vkWriteResourceDescriptorsEXT`**（驱动序列化，host 内存参数按签名传入）。
- **memory type 判据（外部评审收编）**：init 时 `vkGetPhysicalDeviceMemoryProperties` 一次性 dump 全 type 到日志，选 `DEVICE_LOCAL|HOST_VISIBLE`（5090 ReBAR 窗口）；查 `HOST_COHERENT` 标志：coherent → 写后免 flush；非 coherent → 显式 `vkFlushMappedMemoryRanges`；只有纯 device-local → CPU-visible staging + 拷贝（垫底）。
- 堆 buffer 本身：raw `vkCreateBuffer`（usage = `VK_BUFFER_USAGE_2_DESCRIPTOR_HEAP_BIT_EXT`（若 1.4.357 有此位则用；查 `VK_BUFFER_USAGE_2` 枚举确认，任务 2 前 30 秒 grep）+ transfer 位），内存绑上面的 type；`vkGetBufferDeviceAddress` 取基址。

## §4 GpuBuffer 设备地址暴露（arena 用）

**已核实**：`VulkanGpuBuffer`（`vulkan/VulkanGpuBuffer.java`）= `vkBuffer()` long 访问器 + **VMA 分配**（`device.vma()` / `vmaDestroyBuffer`），无 deviceAddress 字段。
**实施决定**：arena 用官方 `GpuDevice.createBuffer`（usage 按 §3 的 descriptor-heap buffer 需求选；若官方 usage 位不够则 raw 自建）；设备地址一律 `VK12.vkGetBufferDeviceAddress(VkHandles.device, vkBuffer)`（对任意 VkBuffer 有效，VMA buffer 是正经 VkBuffer）。block tile 的 raw SSBO（R-b）= 捕获句柄上 `vkCreateBuffer(STORAGE|TRANSFER_DST)` + `vkBindBufferMemory`（内存 type 判据同 §3）。

## §5 VRS 2026 形态与 pipeline 注入点

**已核实**：1.4.357 头里 VRS 只有 **NV 覆盖调制族**（`VkPipelineCoverageModulationStateCreateInfoNV`（sType=1000152000）+ `vkCmdSetCoverageModulation*NV` 动态命令 + `extendedDynamicState3CoverageModulationMode` 等 feature）；**无独立 2026-core VRS 结构体**。5090 驱动（2026 代）按 NV 通道提供（"2026 里程碑设备保证"在此 = 驱动保证 NV 通道可用）。
**实施决定**：走 **pipeline 静态态**（spec"一个 pass 级设置、无代码分支"的落法）：`VkPipelineCoverageModulationStateCreateInfoNV`（2×2 调制：`coverageModulationMode` + `pCoverageModulationMap`（`VkCoverageModulationRegionNV` 编码，任务 3 开写前读头文件枚举定值））→ 挂到 `VkGraphicsPipelineCreateInfo.pNext` 链。注入法：mixin `@Redirect` 指向 `VulkanRenderPipeline.compile` 里 `.pNext(renderingInfo)` 这次方法调用（`VkGraphicsPipelineCreateInfo.pNext` 的 LWJGL 方法调用点），handler 判 `pipeline.getLocation() == 我们的 PIPELINE_LOCATION` → 返回我们的链头（`vrsStruct.pNext(renderingInfo)` 或 `renderingInfo.pNext(vrsStruct)`，链序以 VVL 验证）；非我们的 pipeline 原样返回。@Redirect 对 LWJGL 方法调用可行（类已加载、调用点单一）；不行则退 raw 自建 pipeline（复刻 renderingInfo：color 格式来自主目标、depth=126、viewMask 0——上面 compile() 代码已抄全）。
- **feature 门**：pipeline 静态态路径不需要设备 feature（NV 结构体 + 驱动能力即可）；若 VVL 报"结构体要求 feature"，再查 5090 的 NV feature 位并补进任务 1 的 pNext 链（届时手术 mixin 加一个 NV feature 结构体）。

## §6 VkBindHeapInfoEXT reservedRange 语义

头文件只见字段（`reservedRangeOffset/Size`），registry 无 prose。
**实施决定**：S1 一律传 **0 / 0**（不用保留区）；任务 2 首跑 VVL 若报保留区违规，按报错信息回填（并同步更新本条）。

## §8 任务 2 侦察收口（2026-09-16 实施前定稿）

1. **官方 VBO/IBO 绑定 = 1 代 handle 路径**：`VulkanRenderPass.setVertexBuffer` → `VK12.vkCmdBindVertexBuffers(cbu, slot, VkBuffer handle 数组, offset 数组)`；`setIndexBuffer` → `VK12.vkCmdBindIndexBuffer(cbu, vkBuffer, 0, type)`。顶点输入 ABI 与 §1 裁决完全一致，本任务不动。
2. **官方 uniform = push descriptor（非持久 set）**：`drawIndexed` 时 `pushDescriptors()` 遍历 `pipeline.layout()`（= 全部 BGL 合并成的**单个** set layout 的 entries）**逐个**建 `VkWriteDescriptorSet`（UNIFORM_BUFFER→pBufferInfo / SAMPLED_IMAGE→pImageInfo / TEXEL_BUFFER→新建 BufferView），UNIFORM_BUFFER 条目**强制** `usage & 128`（USAGE_UNIFORM）否则抛 `IllegalStateException("Uniform buffer X must have GpuBuffer.USAGE_UNIFORM")`、`uniforms` map 缺值则抛 "Missing uniform X"；最后 `KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cbu, layoutSet=0, pipelineLayout, set=0, writes)`。
   → **实施推论**：phantom 绑定必须 `renderPass.setUniform("VBO"/"IBO", slice)` 填充（双通道：classic push 描述符 + 堆 mapping 重定向，同一 VBO/IBO 地址两边都指向它）；墙的 VBO/IBO 创建 usage 升为 `VERTEX|UNIFORM` / `INDEX|UNIFORM`（`VulkanConst.bufferUsageToVk` 逐位 OR 翻译，32→128、64→64、128→16，组合合法）。
3. **binding 索引解析**：`VulkanRenderPipeline` = **public record**（组件含 `layout` → 公共访问器 `layout()` 返回 `VulkanBindGroupLayout` record(handle, List<Entry>)，Entry=public record(type,name,texelBufferFormat)）。`GpuDevice.precompilePipeline(RenderPipeline)` public（返回缓存的 CompiledRenderPipeline）→ render 时 `instanceof VulkanRenderPipeline` 后**按名扫描** entries 定位 "VBO"/"IBO" 索引（首帧 setPipeline 已完成编译，无竞态；扫描失败本帧降级 classic 路径、次帧自愈）。
4. **LWJGL 3.4.1 函数分布（javap 实证）**：buffer/memory/map/flush = **VK10** 全 n 变体（`vkCreateBuffer(VkDevice, createInfo, null, long[])`、`vkBindBufferMemory(dev, buf, mem, 0)`、`vkGetBufferMemoryRequirements(dev, buf, req)`、`vkAllocateMemory(dev, info, null, long[])`、`vkMapMemory(dev, mem, 0, size, 0, PointerBuffer)`、`vkUnmapMemory`、`vkFlushMappedMemoryRanges(dev, VkMappedMemoryRange 单对象变体)`、`vkDestroyBuffer`/`vkFreeMemory`）；`vkGetPhysicalDeviceProperties2(VkPhysicalDevice, VkPhysicalDeviceProperties2)` 与 `vkGetPhysicalDeviceMemoryProperties2(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties2)` 在 **VK11**（**没有 1 代 VkMemoryProperties 类**，走 2 代 wrapper 的 `memoryProperties()` 内嵌值）；`vkGetBufferDeviceAddress` = `VK12.nvkGetBufferDeviceAddress(VkDevice, long)`。`VkBuffer`/`VkDeviceMemory` **没有 handle 类**（全 long 操作）。
5. **n 变体对象参数**：`EXTDescriptorHeap.nvkCmdBindResourceHeapEXT(VkCommandBuffer, long)` 与 `nvkWriteResourceDescriptorsEXT(VkDevice, int, long, long)` 收 **handle 对象** → `new VkCommandBuffer(cbuAddr, VkHandles.deviceWrapper)`（构造器调 `device.getCapabilities()`，deviceWrapper = 捕获的官方真 wrapper，安全）；`VkHandles` 增 `deviceWrapper` 字段，`VkHandlesProbeMixin` 顺手捕获 `this.vkDevice`。新增探针 `VulkanCommandEncoderProbeMixin`（@Shadow `currentCommandBuffer` + `dhvkCurrentCbu()`）。
6. **mapping 结构定稿**：`VkDescriptorSetAndBindingMappingEXT{sType=1000135005, descriptorSet=0（push set layoutSet 0 = VK_NULL_HANDLE，VVL 裁决）, firstBinding, bindingCount=1, resourceMask=0x20（VK_SPIRV_RESOURCE_TYPE_UNIFORM_BUFFER_BIT_EXT，1.4.357 头 L16732 实证）, source=0（HEAP_WITH_CONSTANT_OFFSET）, sourceData.constantOffset{heapOffset=int, heapArrayStride=0}}`；**每 binding 一个 struct**（两个不同堆偏移不能用一个 struct 的 arrayStride 覆盖），链挂 `VkBindHeapInfoEXT.pNext`。heapOffset = 表内绝对槽位偏移（S1 恒绑整表 rangeOffset=0）。`heapRange` = 整表 {baseDeviceAddr, sizeBytes}，`reservedRange 0/0`（§6）。
7. **堆 API 属性/常量**：`VkPhysicalDeviceDescriptorHeapPropertiesEXT`（sType 字面量 1000135008）提供 `bufferDescriptorAlignment/Size`、`maxResourceHeapSize`、`resourceHeapAlignment`、`minResourceHeapReservedRange`；写入 = 路径 A（host 直写 16B raw `VkDeviceAddressRangeEXT`，仅当 descSize==16 且 host-visible 自动成立，非 coherent 写后 `vkFlushMappedMemoryRanges`）/ 路径 B（`vkWriteResourceDescriptorsEXT`，`VkResourceDescriptorInfoEXT{sType=1000135002, type=6（UNIFORM_BUFFER）, data.pAddressRange}`，hostRange = 堆 host 窗口槽位；纯 device-local 时 256B host-visible coherent scratch）。env `DHVK_HEAPWRITE=A|B` 可强制，缺省 auto。堆 buffer usage = `VK_BUFFER_USAGE_DESCRIPTOR_HEAP_BIT_EXT`（int 位 0x10000000，1 代 usage 字段放得下）+ TRANSFER_SRC|DST。
8. **BDA feature 位**：官方 `REQUIRED_DEVICE_FEATURES` 无 bufferDeviceAddress 条目，run12 实证"BDA 扩展已启用但 feature 位未显式置"的设备创建 VVL 零报错 → 本任务**不动手术**；若 run13 的 `vkGetBufferDeviceAddress` 被 VVL 点名缺 feature，补 `new VulkanFeature(new VulkanPNextStruct(51, VkPhysicalDeviceVulkan12Features.SIZEOF), "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS)`（官方同款字面量 sType=51 姿势，VulkanBackend L~72 实证）。
9. **堆源可辨识性（诚实边界）**：官方机制恒写 classic 描述符（缺值即抛），故"堆是否真被驱动用作地址来源"在 S1 只能靠 ① VVL 对 mapping/heap-bind 全链零报错 + ② shader 内判别式（`probe = u_vbo_phantom.pad.x + u_ibo_phantom.pad.x`；健康 = -400+0 → 位移 0 像素不变；堆失效读 0 → 墙 A 瞬移到世界原点，肉眼不可误判）共同背书；数据流真走堆的证明留 S2/S4 per-cell 流式。
10. **junit 基建**：`mod/build.gradle` 无 test 配置 → 加 `testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'` + `test { useJUnitPlatform() }`；gradle 缓存无 junit → 首跑 `:mod:test` 去掉 `--offline` 让 mavenCentral 下载（失败则按计划 fallback 游戏内 log 断言）。

## §7 探针状态（S0 悬案）

S0 的 "vk handles: not captured yet" 日志出自 mod 启动时刻（设备尚未创建）——**虚惊**；但 `capture()` 成功无日志，无法事后证明。
**修复（已写盘，待首跑验证）**：`VkHandles` 增 `physicalDevice` 字段（四参 `capture`），`capture()` 成功即 `LOGGER.info("[dhvk] vk handles captured: {}", format())`（format 含 `pdev=0x%x`）。
**pdev 取法偏离 plan Step 8（实测修正）**：26.2 的 `VulkanDevice` **没有** physical device 字段——构造器参数 `VulkanPhysicalDevice` 是包装类且构造末尾被 `close()`（`VulkanBackend` L133/L188 流）。改为 `this.vkDevice.getPhysicalDevice().address()`：LWJGL `VkDevice` 3.4.1 确有 `getPhysicalDevice()`（javap 核实），官方同款用法见 `VulkanBackend` L199/L440；pdev 属 instance 级，地址值在 device 存活期稳定。首跑日志出现 `vk handles captured: ... pdev=0x...` = 销案；否则按 spec §7 R6 重布（改挂 `VulkanDevice` 构造 HEAD + 字段读取，或 `RenderSystem.getDevice()` 首调点）。

---

## 任务 0 进度（执行记录）

1. ✅ 探针代码已写盘（`VkHandles.java` 四参 capture + 确认日志；`VkHandlesProbeMixin` 按实测修正取 pdev——见 §7）；`dhvk.mixins.json` 无需改（探针已注册）；
2. ✅ `./gradlew :mod:build --offline` BUILD SUCCESSFUL（2026-09-14，探针两文件增量编译通过）；
3. ⏳ **问用户** → 标准档 runClient（带 `--vulkanValidation`，无 VK_LAYER_SETTINGS_PATH）→ grep 日志确认 `vk handles captured: ... pdev=0x...` + VVL 对照基线；
4. ✅ 笔记 §1.6 已补全（"自定义 BGL 入口"= precompilePipeline 路径 + entry 派生规则 + phantom binding 判据）；
5. ⏳ 首跑销案后收口提交（`S1 任务0: 六项读码先决勘察 + 探针确认日志/physicalDevice 捕获`，含本笔记 + 两个探针文件）。

**关键文件路径**：官方树 `mc-src/client/com/mojang/blaze3d/`（vulkan/ pipeline/）、`net/minecraft/client/renderer/BindGroupLayouts.java`、官方 shader 已提取在 `/tmp/official-shaders/`（**tmpfs，重启即没**；任务 2 若再要 terrain.vsh 就重新 `unzip -p mc-src/artifacts/client.jar assets/minecraft/shaders/core/terrain.vsh`）、`mod/src/main/resources/assets/dhvk/shaders/core/far_terrain.{vsh,fsh}`、shader 离线编译 = `mod/scripts/compile-spirv.sh <outdir> <x.vert>`（**必须 .vert 后缀**；`#moj_import` 宿主 glslang 不认识，离线验证走内联副本——本笔记 §1.1 的配方）。


## 任务 1 执行备忘（2026-09-14 夜间，未收口）

**已写盘（构建通过，未验证运行）**：
- `mixin/VulkanBackendDeviceSurgeryMixin.java`：@Redirect 重定向外层 createDevice 对内层静态 createDevice 的唯一 INVOKE（字节码已 javap 核实 target = 内层 createDevice 3 参）。handler 签名 = 目标实参 + 调用方实参（window/ShaderSource/GpuDebugOptions/Runnable），**不带 CallbackInfoReturnable**（0.8.7 静态目标 @Redirect：handler 返回值直接替代原调用）。手术 = 物理设备 hasDeviceExtension("VK_EXT_descriptor_heap") 才 `deviceExtensions.add` + `vulkanFeatures.add(DESCRIPTOR_HEAP_FEATURE)`；feature 链结构体由官方 findOrCreateStructInPNextChain 自动 ncalloc 插链（已核实机制，sType=1000135009/SIZEOF=24/DESCRIPTORHEAP 偏移 16 已运行时探针验证）。内层 createDevice 是 private → @Shadow 声明 + 无定界符调用。
- `DhVkClient`：`surgeryEnabled()`(env DHVK_NOSURGERY) / `gatePassed()` / `disabled`(volatile) / `checkDeviceGate()`——首帧 ensureBuffers 里跑，用 VK11.nvkGetPhysicalDeviceFeatures2(new VkPhysicalDevice(裸句柄, null), f2.address()) 查 descriptorHeap（LWJGL 3.4.1 该函数在 **VK11** 不在 VK12，n 变体第一参也要对象）。
- `FarTerrainRenderer`：attach/render 双守卫 + ensureBuffers 后**二次** disabled 检查（首帧竞态：帧图 pass 在 gate 判定前已挂，不查会空 buffer 崩 GL 回退后端——已实证 NPE）。
- `dhvk.mixins.json` 已注册手术 mixin。

**mixin 0.8.7 踩坑沉淀（本任务实证）**：无 @Local 注解；无 @Redirect.range；静态目标 @Redirect handler 不接 CIR；回调类在 .injection.callback 包；private 方法调 @Shadow。

**未解之谜（下次运行要查）**：第 3 次运行实证——手术 mixin 成功触发（"device surgery applied" 日志 ✓），但 VVL 拒设备创建：报 pNext 链含"unexpected VK_STRUCTURE_TYPE_APPLICATION_INFO"（appinfo 只在 VulkanInstance 用，设备链里的 AppInfo 是**loader 注入**的；之前无堆扩展时 VVL 零报错，启用了 2026 代堆扩展后 VVL 才开始 enforce 该 VUID）。且 VVL 转储的链**没有我们的 DescriptorHeap 结构体**（9 节点全官方）→ 怀疑应用层链里就缺席。已装临时探针：@Redirect VK12.vkCreateDevice（字节码确认 target 类是 VK12 继承写法）打印应用层 pNext 链 sType 序列 + 扩展列表 + base features。
**下一步**：问用户 → 标准档跑 → 读 probe 日志 → 若应用层链确实无堆结构体，再查 set() 调用链；若应用层有、VVL 层丢，则是 loader/VVL 交互。修复后跑正路径（gate PASSED + 双墙 + VVL 零）与负路径（DHVK_NOSURGERY=1，gate FAILED + 无堆扩展 + 游戏照常）。


## 任务 1 run 日志(2026-09-15 深夜, run6/run7/run8 进行中)
- **run6 全量 hex 取证 → 真凶定案**: sType=0 节点 = LWJGL 3.4.1 的 STYPE 静态字段**半初始化**(类初始化/mixin apply 时刻读还是 0, 运行时才填充); 节点内存完好(24B 保留区无越界, 之前"ncalloc 8 字节保留被覆盖"假说被 hex 证伪)。修复 = 全仓三处(结构体描述符/链净化器/门槛查询)改读 DhVkClient 字面量常量: DESCRIPTOR_HEAP_FEATURES_STYPE=1000135009 / DESCRIPTOR_HEAP_OFFSET=16 / DESCRIPTOR_HEAP_STRUCT_SIZE=24 (1.4.357 头文件 vulkan_core.h L674/L16913 实锤)。
- **run7**: STYPE 修复生效(probe set 含 [1000135009:descriptorHeap], node#1 hex 全清白, AppInfo 误报消失) → VVL 剥出下一层: VUID-01387 缺 VK_KHR_maintenance5 (descriptor_heap 的依赖扩展)。
- **依赖闭包**(refs/vulkan-headers registry/vk.xml L23693): `((extended_flags,maintenance5)+(BDA,1.2)),1.4`; extended_flags 依赖 gpdp2+1.1(核心满足), m5 依赖 dynamic_rendering+1.3(官方表已有 DR ✓)。run8 补丁 = 条件式补驱动暴露的闭包成员(maintenance5/extended_flags/BDA)。
- **未决**: 官方实例/设备 = 1.2(VulkanInstance L43 显式 1.2, 设备继承), heap depends 版本底 1.4; run7 VVL 未挑版本 → 若 run8 挑, 再做版本手术(实例+设备提到驱动上限)。
- **下一步**: run8(标准 profile+VVL) → 负路径 DHVK_NOSURGERY=1 → 移除临时诊断块 → 任务 1 提交。
- 0.8.7 补充实证: **异类**静态靶子(VK12.vkCreateDevice)的 @Redirect handler 必须 static(run4 炸: "non-static callback method targets a static method"); 同类静态靶子+非静态 handler 可用(run3 实证)。LWJGL 3.4.1 MemoryStack.calloc(int,int) 返回 ByteBuffer 非 long(用 ncalloc 三参版拿地址)。

- **run8(2026-09-15 深夜)**: 依赖闭包补齐后 **VVL 零消息、Vulkan 设备真立起来**(graphics backend Vulkan, 1.4.351/615.71.09, descriptor_heap(D)+maintenance5(D)+BDA(D) 全启用, handles captured) → 新 NPE: DhVkClient.checkDeviceGate 里 new VkPhysicalDevice(handle, null) 的构造器调 instance.getCapabilities() 必炸 → 已改 n 变体裸 long 直传 pdev。run9 = 正路径最后候选。
- **遗留观察**: capture 日志 pool=0x0(首帧时命令池尚未建, 任务 2 前需确认捕获时机/补捕获)。
- run8 NPE 终修: n 变体只收 VkPhysicalDevice 对象(裸 long 不匹配) → VkHandles 增 pdevWrapper 字段, VkHandlesProbeMixin 捕获 this.vkDevice.getPhysicalDevice(), 门槛查询直用 wrapper; 构建绿, 待 run9。
- **run10(负路径)发现门槛语义 bug**: 无手术时 `device gate PASSED` 误判 —— vkGetPhysicalDeviceFeatures2 查的是**物理设备**能力(5090 天生支持 heap), 与设备是否启用扩展无关; Vulkan 无设备级查询 API。终修: DhVkClient.surgeryApplied 由手术 handler 置位(扩展+feature 真入链), 门槛先查它再查物理设备。run11=负路径重跑, run12=正路径复验(新门槛逻辑)。
- **任务 1 收口(2026-09-15 深夜)**: run11 负路径 PASS(gate FAILED: surgery not applied, buffers skipped, VVL 零); run12 正路径 PASS(surgery applied + chain sanitized + gate PASSED + far-terrain buffers created + VVL 消息数=0/VUID=0, 对 vanilla 基线双零)。临时诊断块已移除, 净化器+手术保留。任务 1 完成。

## §9 任务 2 实施记录（2026-09-16 压缩前状态锚点）

**已写盘全部代码**（构建在最终 checkstyle 修正后待复跑）：
- 新建 `mod/src/main/java/dev/dhvk/heap/HeapLayout.java`（slotOffset/totalBytes，S1_SLOTS_PER_CELL=2）+ `DescriptorHeap.java`（驱动属性探测 sType=1000135008 字面量 / 内存类型 dump+三段挑选 / raw vkCreateBuffer usage=堆位+transfer / BDA 基址 / host 映射窗口 / 路径 A 直写+B 序列化(auto: descSize==16&&hostVisible→A; env DHVK_HEAPWRITE 强制; 纯 device-local 256B scratch) / bind() 发 nvkCmdBindResourceHeapEXT + 每 binding 一个 VkDescriptorSetAndBindingMappingEXT 链(descriptorSet=0/resourceMask=0x20/source=0) / close() 有序释放）；
- `DhvkCommandEncoder` 接口 + `VulkanCommandEncoderProbeMixin`(@Shadow currentCommandBuffer, implements 接口) + `CommandEncoderDhvkProbeMixin`(wrapper 桥接, @Shadow backend 字段) —— 渲染器经接口调 `dhvkCurrentCbu()`，不依赖官方 wrapper 类细节；
- `VkHandles.deviceWrapper` 新字段 + `VkHandlesProbeMixin` 捕获 `this.vkDevice`；mixins.json 注册两个新探针；
- `FarTerrainRenderer`：GEOMETRY BGL(VBO/IBO UNIFORM_BUFFER) 入 PIPELINE；vbo/ibo usage 升 VERTEX|UNIFORM / INDEX|UNIFORM（pushDescriptors 的 `usage&128` 强检）；render() 经 systems.CommandEncoder wrapper + setUniform("VBO"/"IBO") 填充 phantom + `dhvkBindHeap()`（setPipeline 后 drawIndexed 前，整表绑定 + 两个 mapping）；`resolvePhantomBindings()` 经 `precompilePipeline(PIPELINE) instanceof VulkanRenderPipeline` 按名扫 layout().entries() 定位索引（首帧竞态降级 classic、次帧自愈）；ensureBuffers 尾部堆 init + `VK12.nvkGetBufferDeviceAddress` 取墙缓冲设备地址写描述符；dispose() 关堆；
- `far_terrain.vsh`：phantom UBO 块 `layout(std140) uniform VBO/IBO { vec4 pad; }` + 堆源判别式 `probe = u_vbo_phantom.pad.x + u_ibo_phantom.pad.x; pos = Position + vec3(probe + 400.0, 0, 0)`（健康=0 位移像素不变；堆失效→墙 A 瞬移世界原点）；
- `mod/build.gradle`：junit-jupiter 5.10.2 + junit-platform-launcher 1.10.2（Gradle 9.7 必声明）+ `test{useJUnitPlatform()}`；`HeapLayoutTest` 已绿（:mod:test BUILD SUCCESSFUL）；
- checkstyle 坑：ImportOrder option=UNDER（static 组紧随非 static、**不留空行**）+ 词典序（pipeline.BindGroupLayout 在 platform 之前）。

**LWJGL 3.4.1 本轮实证补记**（§8 的追加）：`VkDeviceAddressRangeEXT` 字段名 = `address$()`/`size()`（非 deviceAddress/rangeSize）；`VkHostAddressRangeEXT` 的 address 生成怪癖为指针成员（naddress$ 收 ByteBuffer、size 无 long setter）→ 用 `MemoryUtil.memPutLong(structAddr+0/+8)` 裸写；`VkMappedMemoryRange` 字段 = `size`（非 rangeSize）；1.0 核心函数在 **VK10** 全 n 变体 long 签名；`systems.CommandEncoder` wrapper 的 `backend()` 是 protected → 只能 mixin 桥接。

**下一步**：`./gradlew :mod:build --offline` 应全绿（刚修完 test 的 UNDER 空行）→ **问用户** → run13 标准档（预期日志：`descriptor heap ready: ...`（含 memType dump + writePath A/B 裁决）+ `wall geometry registered in descriptor heap` + `phantom bindings resolved in merged layout: VBO=? IBO=?` + 双墙出图 + VVL 零）→ run14 全开档 → 收口提交。风险裁决位：① push set 的 descriptorSet=0 合法性 ② host 直写 A 路径（看 descSize 裁决值 + 墙是否像素不变）③ BDA feature 位是否被点名 ④ 判别式（probe 健康=-400 → 像素不变即背书）。

### run13 红线 + 根因与修复（2026-09-16 深夜）
- **现象**: 窗口弹出入世界(手术生效/链净化/gate PASSED) → 首帧 `vkBindBufferMemory(heap)` rc=-1000011001 崩。VVL 点名: VUID-vkBindBufferMemory-buffer-11408 —— 堆 usage 位 buffer 的内存必须带 DEVICE_ADDRESS 分配位(实测 flags=0)。
- **2026 新值空间全表(javap 实锤, lwjgl-vulkan 3.4.1-snapshot, 旧 spec 记忆全废)**:
  - buffer usage: 1=TRANSFER_SRC 2=TRANSFER_DST 4=UNIFORM_TEXEL 8=STORAGE_TEXEL 16=UNIFORM 32=STORAGE 64=INDEX 128=VERTEX 256=INDIRECT 131072=SHADER_DEVICE_ADDRESS 268435456=DESCRIPTOR_HEAP;
  - **BDA usage 位不再独立存在(收编进 SHADER_DEVICE_ADDRESS)**; **DEVICE_ADDRESS 内存属性位删除**(2026 任意类型均可带 DEVICE_ADDRESS 分配位, 类型挑选不再按属性过滤);
  - 内存属性位: 1=DEVICE_LOCAL 2=HOST_VISIBLE 4=HOST_COHERENT(旧 spec 字面量 2/4/1 反序 → run13 dump 标签打歪, 幸而三段 AND 对排列不敏感, 选对了 type4);
  - `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT = 2`(VK12), 且 **flags 字段移出 VkMemoryAllocateInfo 本体 → pNext 链 `VkMemoryAllocateFlagsInfo`(sType=1000060000, VK11; 成员 sType/pNext/flags/deviceMask)**; 新生代结构体 STYPE 半初始化陷阱 → 字面量 sType。
- **三重根因**: ① 堆内存分配缺 DEVICE_ADDRESS 位(run13 直接崩点); ② vbo/ibo 无设备地址 usage 位 + 无 DEVICE_ADDRESS 内存 → `vkGetBufferDeviceAddress` 三要素不全(下一个必中 VVL 点); ③ 设备 bufferDeviceAddress feature 未启用(手术只补了扩展)。
- **官方机制参照(Phase 2)**: 官方 GpuBuffer 内存全走 **VMA**(`VulkanGpuBuffer.Direct` → `Vma.vmaCreateBuffer`, 全树无裸 vkAllocateMemory); 官方 `createVma` 未设 `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT(=32)`; 设备 = 1.2 API; `VulkanConst.bufferUsageToVk` 把官方 GpuUsage 位一一映射到 2026 Vk usage(无设备地址位映射, 故需手术)。
- **修复(构建全绿)**:
  1. `VulkanBackendDeviceSurgeryMixin`: BDA 扩展在场时向官方 `VK12_FEATURES_STRUCT` 链节点注入 `new VulkanFeature(..., "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS)`(handler 内惰性构造, 避开 <clinit> 序陷阱);
  2. 同 mixin 新增 @Redirect: `createVma` 内 INVOKE `Vma.vmaCreateAllocator`(异类静态靶 → static handler): surgeryApplied 时 `createInfo.flags |= 32`(VMA 内存池认识 DEVICE_ADDRESS 类型; VMA 3.x 对设备地址 usage 的 buffer 自动配分配位 —— 若未自动, VVL 会点 vbo/ibo 内存, 备位 = vmaCreateBuffer Redirect 叠 VMA_MEMORY_ALLOCATE_DEVICE_ADDRESS);
  3. 新 `VulkanConstBdaUsageMixin`(mixin VulkanConst, @Inject RETURN `bufferUsageToVk(I)I`): usage 带 `DeviceAddressUsage.DEVICE_ADDRESS(=1024, 高于全部官方 usage 位)` 且 surgeryApplied → 叠 `VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT`; 官方缓冲无标记位逐位不变;
  4. `DescriptorHeap`: 内存属性字面量 → VK10 常量(2026 值空间); 堆分配走 `VkMemoryAllocateFlagsInfo` pNext 链 + DEVICE_ADDRESS 分配位; 类型 dump 加 flags=0x 裸值;
  5. `FarTerrainRenderer.ensureBuffers`: vbo/ibo usage 叠加 DEVICE_ADDRESS 标记位; 新 `DeviceAddressUsage` 类; mixins.json 注册。
- **run14 = 标准档重跑(红线修复验证)**: 预期 = memory types dump(标签+裸值自洽) → `descriptor heap ready: ...(writePath=A|B)` → `wall geometry registered in descriptor heap` → `phantom bindings resolved` → 双墙出图(墙 A -400 四色不位移 = 判别式健康) → VVL 零; 裁决位不变: ① push set descriptorSet=0 合法性 ② A/B 写路径裁决 ③ VMA 自动分配位 ④ 堆 mapping 与 classic push 描述符共存。

### run14 / run15 / run16a 红线 + VVL 根因与修复（2026-09-16 深夜续）
- **run14 RED**: `@Redirect` 靶描述符写成 `)J` 而 `Vma.vmaCreateAllocator` 返回 **int** → mixin apply 失败(0 targets)。修: 靶描述符 `)I` + handler 返回类型 `int`。教训: @Redirect 靶描述符与 handler 返回类型必须同时匹配。
- **run15 RED(开放→已收口)**: 前戏全绿(手术+bufferDeviceAddress feature/链净化/gate PASSED/VMA 双墙 buffer 建成/type4 ReBAR coherent 选中/bind 通过零 VVL 消息) → `VK12.nvkGetBufferDeviceAddress`(堆 buffer, 我们 MemoryStack 仍活着)处 **VVL 层内部 SIGSEGV**(exit 134)。VVL = Fedora vulkan-validation-layers **1.4.341.0-2.fc44**(stripped 无符号, 无 core)。崩点反汇编(libVkLayer_khronos_validation.so+0x60728a, caller +0x436f17): `test %rbx,%rbx; je skip; cmpl $0x3b9e8321,(%rbx)` = **pNext 链 walker 解引用非空悬空链头读 sType**。
- **run16a(二分, DHVK_NOFLAGS=1 摘链)RED 但形态干净**: VVL 在 `vkBindBufferMemory` **优雅报消息** —— `buffer was created with VK_BUFFER_USAGE_2_DESCRIPTOR_HEAP_BIT_EXT, but the bound memory was not allocated with VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT (VkMemoryAllocateFlags::flags were VkMemoryAllocateFlags(0))`(VUID-11408) + rc=-1000011001 → 我们的 check 抛 IllegalStateException, 干净 Java 崩溃, 没走到 GetBufferDeviceAddress。**实证: NULL 链头被 VVL 优雅处理; 非空(悬空)链头 → walker 解引用 SEGV**。
- **根因(收口)**: VVL 1.4.341 把**调用方栈上 pNext 节点**的裸指针存进自己的状态, 后续 `vkGetBufferDeviceAddress` 再走一遍链; 我们的 `VkMemoryAllocateFlagsInfo` 节点开在 LWJGL MemoryStack 上, `vkAllocateMemory` 返回即弹栈 → 悬空。不是 flags 语义问题, 是**节点存放位置**问题。run16b(HEAPSDA)不必再跑, 判别已被 run15+16a+反汇编三角收口。
- **修复(构建全绿)**: 节点常驻 native heap —— `MemoryUtil.nmemCalloc(32, 16)` + `VkMemoryAllocateFlagsInfo.create(flagsInfoNode)`, `close()` 尾部 `nmemFree`(VVL 的 walker 可能直到 vkFreeMemory 才走完)。字段 `flagsInfoNode`。`DHVK_NOFLAGS`/`DHVK_HEAPSDA` 保留为诊断开关。
- **LWJGL 3.4.1 API 补记(本轮 javap 实证)**: MemoryUtil 原生分配族全部**小写** `nmemAlloc(long)/nmemFree(long)/nmemCalloc(size,align)`(旧 nMemAlloc/nMemFree 已删); 新生代结构体**无 no-arg 构造**, 静态工厂 = `create(long address)`/`calloc()`/`malloc()`/`createSafe(long)`, 无 `calloc(long)`; `calloc(MemoryStack)` 仍在。
- **run17 = 标准档终验(默认路径, 链在, 节点永生)**: 预期 bind 零消息通过 → GetBufferDeviceAddress 走**活**链不崩 → `descriptor heap ready`(type4, writePath=A) → 双墙出图(墙 A -400 四色不位移 = 判别式健康; 墙 B -2000 雾吞) → VVL 零(vs docs/baselines/) → 干净退出(堆 close 有序释放 + VVL 对象追踪零)。红 = run16b 形状再二分或 VVL 升级/裁切检查组。

### run17 红线 + 根因收口到 VVL 自身（2026-09-16 深夜终）
- **修复尝试**: `DescriptorHeap` 把 `VkMemoryAllocateFlagsInfo` 节点从 LWJGL MemoryStack 搬到 **native heap 永生**（`MemoryUtil.nmemCalloc(32,16)` + `VkMemoryAllocateFlagsInfo.create(long)`，close() 尾部 `nmemFree`；2026 LWJGL 小写 nmem* API，无 no-arg 构造）。构建全绿。
- **run17 结果**: 标准档（链在、节点永生）→ 前戏全绿（手术+bufferDeviceAddress/链净化/gate PASSED/type4 选中/**bind 这次通过零 VVL 消息**，因为 DEVICE_ADDRESS 位已带且节点可读）→ `VK12.nvkGetBufferDeviceAddress(堆buffer)` 处 **VVL 仍在同一偏移 `+0x60728a` SIGSEGV**（hs_err_pid266839，caller `+0x436f17`，栈 = nvkGetBufferDeviceAddress ← DescriptorHeap.<init> ← ensureBuffers）。
- **根因收口**: 节点永生后仍崩于同一偏移 ⇒ 悬空指针**不在**我们给的链上（我们的链干净且永生）⇒ **VVL 1.4.341 内部 bug**：`vkAllocateMemory` 记录 pNext 链时的簿记留了失效内部指针，`vkGetBufferDeviceAddress`（descriptor-heap/BDA buffer）走链时解引用悬空内部指针。我们代码/驱动/链均正确。run16a（无链）优雅处理 = 反证（空链头被 `je skip` 跳过，不崩）。
- **待决修复路径**（均需对称作用于 baseline 重采 + mod 跑，保持「VVL 零 vs baseline」diff 有效）:
  - **A 升级 VVL**: 换新版 validation layer（Vulkan SDK / Khronos release），修的是工具本身；需下载（用户规则: 先确认），且要跑一轮验证新版确实不崩。
  - **B VVL 设置文件**: 保持 1.4.341，加 `VK_LAYER_SETTINGS_PATH` 关掉触发 bug 的那组检查（descriptor-heap/BDA 的 GetBufferDeviceAddress 校验）；免下载、版本对称最干净，但要先查准 1.4.341 的确切 knob（可能不存在/粒度粗）。
  - **C 代码侧重排**: 把堆 buffer 的 `vkGetBufferDeviceAddress` 挪到 `vkBindBufferMemory` 之前（未绑定 buffer 走 GetBufferDeviceAddress 合法，VVL 可能不 walk 内存链从而躲过 bug）；零环境改动最贴「baseline vk2026」，但 ① 可能覆盖不全 VMA 的 vbo/ibo（它们被 VMA 先 bind，若 VMA 也塞 pNext 链会同样中招）② 有引入「buffer 未绑定」VUID 消息的风险（破 VVL 零）。
  - **诊断 run（可选，跑前先问）**: `DHVK_SKIPHEAPADDR=1` 跳过堆的 GetBufferDeviceAddress，让运行推进到 VMA 的 vbo/ibo 的 GetBufferDeviceAddress —— 若 VMA buffer 同样 SEGV ⇒ 证实 VVL bug 是泛化的（任何 BDA+链 buffer），代码侧 C 注定覆盖不全，应走 A/B。

### 路线 C 落地（用户选定, 2026-09-16 深夜）
- 用户拍板走 C（零环境改动, 代码侧消化 VVL 1.4.341 的 1 代 GetBufferDeviceAddress walker bug）。两把刀:
  1. `DescriptorHeap`: 堆 buffer 的 `nvkGetBufferDeviceAddress` **挪到 vkBindBufferMemory 之前**（未绑定 buffer 的 1 代校验不走内存状态链; 偏移 0 地址 bind 前后不变; 本 VVL 构建此命令仅 4 条 VUID, 无"必须已绑定"检查, 预绑定查询应零消息）;
  2. `FarTerrainRenderer.bdaAddress2()`: VMA 的 vbo/ibo 躲不开"先 bind 后查" → 换 **2 代 `VK12.vkGetBufferDeviceAddress(VkDevice, VkBufferDeviceAddressInfo)`**（javap 确认 3.4.1 有该 2 代入口, `sType$Default()` 可用, pInfo 空链）—— VVL 的 2 代是独立验证函数, 1 代 walker 够不着。
- 诊断开关 `DHVK_SKIPHEAPADDR` 保留。构建全绿（含 checkstyle: MemoryStack/VkBufferDeviceAddressInfo 插入序符合 ImportOrder）。
- **run18 = 标准档终验（默认路径, 无 env）**: 预期堆查询(pre-bind)与 vbo/ibo 查询(2 代)都过 → `descriptor heap ready` → `wall geometry registered` → 双墙出图像素不变（墙 A -400 四色）→ VVL 零 → 干净退出。红 = 若堆的 pre-bind 查询仍崩（VVL 按内存全局表走链）→ 堆也切 2 代; 若 vbo/ibo 2 代仍崩（bug 在共享 helper）→ C 路到头, 升级 VVL（A 路）。

### run18 红线（路线 C 第一刀未接住, 2026-09-16 深夜）
- 现象: 堆 buffer 的 **pre-bind** 1 代 `nvkGetBufferDeviceAddress` 仍在同一偏移 `+0x60728a` SEGV（hs_err_pid270237, caller 同 `+0x436f17`）——与 bind 状态无关。
- 新判读: 悬空指针既不在我们给的 pNext 链上（run17 已证: 链节点永生仍崩）, 也不因 bind 与否而变（run18 已证: pre-bind 仍崩）⇒ **VVL 1.4.341 对"descriptor-heap usage buffer"的 1 代 GetBufferDeviceAddress 验证路径持有失效内部指针**（对象状态簿记 bug, 触发条件 = 1 代调用 + 堆 usage buffer, 与我们的一切输入无关）。
- 推论: ① vbo/ibo 的 2 代查询还没机会执行（崩在前面的堆查询）; ② 若 2 代与 1 代共享 bug helper, 则 C 路两把刀都不够, 应直接升级 VVL（A 路）。
- **run19 = C 路终试**: 堆查询也切 2 代（并恢复 post-bind 顺序, 单一机制 = "全部设备地址查询走 vkGetBufferDeviceAddress2"）→ 若全绿收口; 若堆的 2 代仍崩同一偏移 ⇒ C 路判死, 转 A 路（升级 VVL, 自定义 VK_LAYER_PATH 目录放新版 .so, baseline 同版重采）。

### run19 大破 + 残余三线（全部 2 代查询, 2026-09-16 深夜）
- **VVL SEGV 彻底退场**: 三次设备地址查询(堆+vbo+ibo)全走 2 代 `vkGetBufferDeviceAddress2` → 无崩溃; 游戏运行 ~15s, 关窗 → `descriptor heap closed` 有序释放 → BUILD SUCCESSFUL 干净退出, VVL 对象追踪零泄漏。
- **vbo/ibo 2 代查询返回真地址**(vbo@0x184158976 ibo@0x184159104) → 2 代 API 链路完全健康(sType$Default 对 VkBufferDeviceAddressInfo 无半初始化问题); phantom 绑定解析 VBO=0 IBO=1; 墙几何写入堆。
- **残余红线三项**(均为 VVL 消息, 非崩溃, 集中在 vkCmdBindResourceHeapEXT):
  1. `baseDev=0x0`: 堆 buffer 自己的 2 代查询返回 0(同形状调用 vbo/ibo 却拿到真地址) → 唯一差异 = 堆 usage 只有 HEAP+TRANSFER 无 SHADER_DEVICE_ADDRESS 位 → 疑驱动要 SDA 位才给真地址 → **run19b 验证: DHVK_HEAPSDA=1(免重编, 纯 env)**;
  2. VUID-vkCmdBindResourceHeapEXT-pBindInfo-11233: `reservedRangeSize(0) < minResourceHeapReservedRange(96768)` → 驱动要求堆内 ≥96KB 保留区 → 堆须从 128KB 扩(保留区 96768B + 8192 槽表 131072B = 227840B → 取 256KB, 保留区置尾部, 描述符表头 131072B 内不与之重叠);
  3. VUID-VkDeviceAddressRangeEXT-size-11411: `heapRange.address=0` = 第 1 项连锁(baseDev=0) → 修 1 自动消失。
- 下一步: run19b(DHVK_HEAPSDA=1) 验证第 1 项判读 → 落实保留区+扩堆 → run20 标准档全绿 → 全开档 → 收口提交。

### run20 设计定稿：2026 静态映射收口（2026-09-17 凌晨）
侦察报告 = `docs/s1-pipeline-surgery-recon.md`（官方管线解剖 + LWJGL 字面量，全部一手证据）。
**规约核心转变（2026 原文已抓 /tmp/dh-spec.txt）**: mapping 是管线创建期静态声明
（`VkShaderDescriptorSetAndBindingMappingInfoEXT{mappingCount,pMappings[]}` 挂
`VkPipelineShaderStageCreateInfo` pNext；经典 shader-module 路径即此通道）；
每帧 `vkCmdBindResourceHeapEXT` 的 `VkBindHeapInfoEXT.pNext 必须 NULL`，只带
`heapRange + reservedRangeOffset/Size`（偏移相对 heapRange 起点）；
管线必须带 `VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT`（2026 = **68719476736L, 64 位,
EXTDescriptorHeap**）→ 经 `VkPipelineCreateFlags2CreateInfo`（sType=**1000470005**, VK14）
pNext 节点注入（`VkGraphicsPipelineCreateInfo` 有类型化 `pNext(VkPipelineCreateFlags2CreateInfo)` 重载）。
**run19b 实证**: 堆 usage 叠加 `VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT` 后驱动才吐真地址
（baseDev=0x4140000）→ 该位从"诊断开关"转正为**无条件**。
**layout 铁律（侦察 Q3）**: set layout 与 BGL 严格 1:1（binding 号 = entry 下标）→
phantom 绑定**保留**在 BGL（连同 setUniform 推送填充暂留, 让 VVL 仲裁"映射绑定上的冗余推送"
是否违规; 若 VVL 点名再撤条目+自建超集 layout）。mapping 的 firstBinding = shader Decoration
静态值（vsh phantom 块 set0 binding0/1），与 BGL 声明序一致（实测 VBO=0 IBO=1）。

**run20 改动清单**（全部落码后构建 → 标准档 run20）:
1. `DescriptorHeap` ctor: 堆 usage 无条件含 SDA 位; `sizeBytes = alignUp(tableBytes + reservedBytes, 4096)`
   （reservedBytes = `VkPhysicalDeviceDescriptorHeapPropertiesEXT.minResourceHeapReservedRange()` 实测 96768;
   tableBytes 沿用现 131072 计算）; 新增字段 reservedOffset/reservedSize;
   `bind(cbu, rangeOffset, rangeSize)` 简化: **mappings 参数删除**, pNext=0(calloc 天然),
   heapRange={baseDev+rangeOffset, rangeSize}, reservedRangeOffset=reservedOffset, reservedRangeSize=reservedSize。
2. 新 mixin `VulkanRenderPipelineSurgeryMixin`（@Mixin VulkanRenderPipeline, 异类静态 handler ×2）:
   - 靶1 `VkGraphicsPipelineCreateInfo.pNext(Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;)...`:
     armed 时建**永生** flags2 节点（nmemCalloc+create, sType=1000470005, pNext=renderingInfo,
     flags2=68719476736L）→ `ci.pNext(node)`; 否则原样透传;
   - 靶2 `VkPipelineShaderStageCreateInfo.pName(Lorg/lwjgl/system/Pointer;)...`:
     armed 且 `stage.stage()==1`(VERTEX) 且 `DhVkClient.mappingInfoNode!=0` 时
     `stage.pNext(VkShaderDescriptorSetAndBindingMappingInfoEXT.create(mappingInfoNode))` 后透传;
   - method 过滤 = "compile"（VulkanRenderPipeline 唯一同名静态方法, 侦察已钉）。
3. `DhVkClient`: +`public static volatile boolean pipelineSurgeryArmed;` +`public static volatile long mappingInfoNode;`
4. `FarTerrainRenderer`: 新方法 `ensureHeapSurgery()`（静态一次, render() 里 ensureBuffers 之后、setPipeline 之前调用）:
   建 mapping 数组+info 节点（全 nmemCalloc 永生, 不 free —— 管线销毁晚于我们的 dispose）:
   m0={sType=1000135005, descriptorSet=0, firstBinding=0, bindingCount=1, resourceMask=32(VK14
   UNIFORM_BUFFER), source=0(HEAP_WITH_CONSTANT_OFFSET), sourceData.constantOffset{heapOffset=heap.slotOffsetOf(0,0), heapArrayStride=0}},
   m1 同但 firstBinding=1, heapOffset=heap.slotOffsetOf(0,1);
   info={sType=**1000135006**, mappingCount=2, pMappings=数组指针};
   置 mappingInfoNode+armed=true → `precompilePipeline(PIPELINE)`（强制首编在手术下发生）→ armed=false
   → 沿用 resolvePhantomBindings 的按名扫描做映射-布局一致性校验（VBO=0 IBO=1 须与 shader 一致, 不一致打 ERROR 日志）。
   render(): `dhvkBindHeap` 改调 `heap.bind(cbu, 0L, heap.sizeBytes())`（无 mapping 参数）。
5. mixins.json client 列表 +`VulkanRenderPipelineSurgeryMixin`。
**run20 预期**: VVL 零（三旧病: pNext-must-be-NULL 消失/mapping 改静态/reservedRange 满足; 新病候选:
推送填充与静态映射并存的 VUID, 届时听 VVL 点名再裁）; 墙上位（堆路独供时判别式仍健康）; 干净退出。
红 = 映射-布局错位 / flags2 节点链问题 / 推送冗余 VUID → 逐项按 VVL 原文裁。

### run20 落码记录（2026-09-17，构建全绿）

清单五条全部落码，`:mod:build --offline` 全绿（checkstyle 绿，HeapLayout 测试绿）。落码中 javap 双验
出的**侦察报告修订**（权威形态已写进代码注释）:
- 靶1 target 类 = **`$Buffer`**（官方 createInfo 是 `VkGraphicsPipelineCreateInfo.Buffer` 包装型,
  字节码 invoke 落在 $Buffer 上）: `VkGraphicsPipelineCreateInfo$Buffer.pNext(VkPipelineRenderingCreateInfoKHR)`
  → handler 返回 $Buffer;
- 靶2 `pName` 吃 **ByteBuffer**（官方 `nameMain = stack.UTF8("main")`, 3.4.1 无 pName(Pointer) 重载）:
  `VkPipelineShaderStageCreateInfo.pName(Ljava/nio/ByteBuffer;)`;
- 3.4.1 代码生成怪癖 ×3: ① struct 侧 `VkPipelineCreateFlags2CreateInfo` 只有 `pNext(long)` 无 typed
  重载 → 传 `renderingInfo.address()`; ② info 节点无 `mappingCount` fluent setter → 全走 n-静态 setter
  （nsType/npNext/nmappingCount/npMappings）; ③ `VkDescriptorSetAndBindingMappingEXT$Buffer.create`
  是 6 参内部工厂 → 公共构造器 `new Buffer(裸地址, 2)` 包裹（无所有权, 不回收）。
- 永生节点: flags2 节点**每 compile 新建**（不复用: 节点 pNext 指向"本次" compile 的栈上
  renderingInfo, 跨 compile 复用 = 第二次 vkCreateGraphicsPipelines 吃悬空链; 官方源码 L200/L208
  双调用点实证）; mapping 数组+info 节点 = ensureHeapSurgery 一次性建, 全 native heap。

落码清单 = 设计定稿节五条一一对应: DescriptorHeap（SDA 无条件 / 表区+预留区 4096 对齐 /
bind 三参化 pNext=0 / 槽位边界改 tableBytes / ready 日志加预留区读数）, DhVkClient（armed+node
两旗标）, VulkanRenderPipelineSurgeryMixin（新建, 两处 static @Redirect, method="compile"）,
FarTerrainRenderer（ensureHeapSurgery+writeHeapMapping, render 时序 ensureBuffers→surgery→setPipeline,
dhvkBindHeap 三参, dispose 手术态复位）, mixins.json 注册。

待用户确认 → run20 标准档（无 env）。

### run20 结果（2026-09-17，RED，高信息量崩溃）

**堆侧全绿**: baseDev=0xe340000（非零, SDA 转正成功）/ table 131072B + reserved 96768B（预留区
落位, VUID-11233 未再出现）/ type4 DL|HV|HC / writePath A / VBO/IBO 地址注册成功。
**手术链全被 VVL 读通**: flags2 链（sType/64 位值）+ 静态映射链均被 VVL walker 完整解析
（报错原文引用 pNext<VkPipelineCreateFlags2CreateInfo>.flags 的位值）→ 两处 @Redirect 结构上
全部正确。
**病灶 = VUID-VkGraphicsPipelineCreateInfo-flags-11311**（run20 前未排的规约暗桩）:
带 VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT 的管线 **layout 必须 VK_NULL_HANDLE**
（规约原句: "In place of descriptor set layouts and pipeline layouts, information can be
provided at pipeline or shader creation time" —— 静态映射整体取代 set/pipeline layout,
null layout 是 descriptor-heap 管线的原生形态）。官方 `.layout(pipelineLayout)` 喂真布局
→ 标准档判 error → vkCreateGraphicsPipelines 返回失败 → crashIfFailure 崩在
ensureHeapSurgery 的 precompilePipeline。
**规约旁证**（/tmp/dh-spec.txt 已读）: ① "Mappings must be declared for all variables with
a DescriptorSet and Binding in the shader resource interface" —— 未映射的经典绑定
（矩阵/雾/全局量, 合并 layout binding2/3/4）在严格 VVL 下会被点名; ② "Push constants
... rely on descriptor set layout state, and are not compatible with descriptor heaps"
（vkCmdPushConstants 不兼容; push descriptor set 的兼容性 run19b 实证: 经典 push 态
在每帧堆绑定后仍有效, 墙位置正确 —— 驱动侧共存成立）。

### run21 设计定稿（最小增量: 第三处手术缝）

1. 新靶3 `VkGraphicsPipelineCreateInfo$Buffer.layout(J)`（javap: 仅 long 单重载, 官方
   L196 实参 = 裸 long pipelineLayout）→ armed 时 `createInfo.layout(0L)`, 否则透传。
   官方 L55 vkCreatePipelineLayout 照常创建/销毁（对象追踪闭合, 只是不喂 createInfo）。
2. 其余全部保持 run20 形态（静态映射只覆盖 VBO/IBO, 经典 push 填充暂留）。
**run21 预期**: 11311 消失; 新病候选 = "未映射 set/binding 变量"VUID（矩阵/雾/全局量
binding2/3/4, 若 VVL 点名 → run22 设计 = 全部映射进堆 + 官方 uniform 缓冲 BDA 手术,
即"descriptor heap 全量替代"路线, 与 S4 方向一致）; 墙上位; 干净退出。

### run21 结果（2026-09-17，RED，预言全中）

null layout 手术命中（11311 消失）。VVL 点名 **VUID-VkGraphicsPipelineCreateInfo-flags-11312**:
带 descriptor-heap 位时**所有** set/binding 装饰变量都必须有映射（规约 1296 行原文实锤, VVL
在标准档把该 VUID 判为失败类）。点名情报: VS 缺 `[Set 0, Binding 3, "DynamicTransforms"]`
（"All 2 pMappings[] were used for another variable already"）; FS 缺 `[Set 0, Binding 4, "Fog"]`
（"pStages[1] does not have a pNext to ... pNext is NULL"）→ Fog 在**片元** shader。
VVL 附逃生提示: "use VK_KHR_shader_untyped_pointers to not require a Set/Binding mapping"
（shader 改用 untyped pointer 读设备地址 = 纯 2026 路线, 需重写 shader 接口, 非 S1 现阶段）。

**run21 侦察收口（全部一手, 已验）**:
- 官方 BGL 定义（mc-src BindGroupLayouts.java）: GLOBALS="Globals", MATRICES_PROJECTION=
  "DynamicTransforms"+"Projection", FOG="Fog" → 合并 layout 共 **6** 个 set/binding 变量:
  VBO(0) IBO(1) Globals(2) DynamicTransforms(3, VVL 钉) Fog(4, VVL 钉) Projection(5 推定)。
- `GpuBufferSlice` = **public record(GpuBuffer buffer, long offset, long length)**（全公开访问器,
  mc-src GpuBufferSlice.java:5）; `VulkanGpuBuffer.vkBuffer()` 公开。
- 官方 buffer 创建**唯一**通道: `VulkanGpuBuffer.Direct` ctor L47
  `bufferCreateInfo.usage(VulkanConst.bufferUsageToVk(usage))` → 全官方缓冲 BDA 化的手术缝
  = 扩展现有 VulkanConstBdaUsageMixin（RETURN 注入点已在此）: surgeryApplied 时恒叠 SDA 位。
- `RenderPass.setUniform(String, GpuBufferSlice/GpuBuffer)` 公开实例方法（RenderPass.java:103/107,
  内部转 backend）→ 捕获用 @Inject TAIL（0.8.7 无 @Local, 形参直取; 实例 @Inject 的 this = RenderPass）。
- 官方 uniform 数据走 ring 缓冲（持久, 每帧 offset 滚动, 同队列上传 → 同 CBU 读天然有序, 无同步兜底）。

### run22 设计定稿（全量映射: 堆描述符指官方 uniform 缓冲 = VBO/IBO 已验证机制的平移）

路线裁决: 弃 vkCmdPushDataEXT+PUSH_DATA（2026 原生但需自序列化 uniform block 布局, 与官方
逻辑双份耦合）, 走**堆指针表**路线（run19/19b 已实证驱动侧经典 push 态与堆绑定可共存;
机制与 S4 几何描述符表同构, "descriptor heap 代替 gl 魔法"原教旨）:
1. **VulkanConstBdaUsageMixin**: `surgeryApplied` 时恒叠 SDA 位（不再看标记位）→ 全官方缓冲
   设备可寻址（VMA BDA allocator flag 已在 run13 就位, 内存侧无需动）。javadoc 同步。
2. **新 mixin `RenderPassUniformProbeMixin`**（@Mixin RenderPass）: @Inject TAIL ×2
   （setUniform 的 slice/buffer 两重载, method 描述符区分）→ `DhVkClient.uniformCaptureArmed`
   为真时 `DhVkClient.uniformSlices.put(name, slice)`（buffer 重载包成全范围 slice）;
   mixins.json 注册。DhVkClient +`uniformCaptureArmed`(volatile boolean) +`uniformSlices`
   (HashMap, render 线程独占)。
3. **管线 mixin**: 靶2 的 `stage.stage()==1` 门去掉 → VS/FS 双 stage 都挂同一 info 节点
   （Fog 在 FS; 若 VVL 报"映射了本 stage 接口外的 binding" → run23 按 stage 分拆映射子集）。
4. **FarTerrainRenderer**: ① ensureHeapSurgery 改 **6 映射**（数组 nmemCalloc(6L*SIZEOF,16),
   `writeHeapMapping` 参数化 slotOffsetOf(i/2, i%2), Buffer.create(arr,6) → n 系 6 参构造器,
   mappingCount=6, 全 resourceMask=32/source=0/descriptorSet=0）; 编译后按名扫描**六名全验**
   （EXPECTED: VBO=0 IBO=1 Globals=2 DynamicTransforms=3 Fog=4 Projection=5, 任一不符 ERROR）;
   ② `dhvkBindHeap` 每帧对 Globals/DynamicTransforms/Fog/Projection 四绑定重写堆描述符:
   slice = uniformSlices.get(name) → addr = BDA缓存(vkBuffer) + slice.offset(),
   size = slice.length() → heap.writeBufferDescriptor（BDA 缓存 = 静态 HashMap<Long,Long>,
   ring 缓冲地址恒, 2 代查询一次）; VBO/IBO 槽位保持 ensureBuffers 一次性写入;
   ③ render() 捕获窗: createRenderPass 后 `uniformCaptureArmed=true; uniformSlices.clear()`,
   dhvkBindHeap 之后 false（render 线程单线程, 无竞态; 与 surgeryApplied 同模式）。
   ④ 官方经典 push 仍照推（6 绑定全堆映射 → 冗余但暂留, 听 VVL 点名 → run23 候选: 按管线
   抑制 push / 或裁决接受）。
**run22 预期**: 11312 消失（六映射全覆盖）; 新病候选 = ① 映射本 stage 接口外 binding（VS 里
Fog 映射 / FS 里 VBO 映射）② 经典 push 冗余 VUID ③ 未映射…应无; 墙上位（uniform 数据经
官方 ring 上传 + 堆描述符取址, 同队列有序）; 干净退出。

### run22 落码记录（构建全绿，待跑）

设计五条全部落码，`:mod:build --offline` 全绿。落码对设计的微调（均 checkstyle/命名层）:
- 捕获表名 = `DhVkClient.UNIFORM_SLICES`（checkstyle ConstantName 全大写规则）;
- BDA 缓存 = FarTerrainRenderer 内 `BDA_CACHE`（全官方缓冲 BDA 化后 ring 缓冲地址恒定, 2 代查询一次）;
- 管线 mixin 靶2 的 `stage.stage()==1` 门已删（VS/FS 双 stage 挂同一 info 节点）;
- EXPECTED_BINDINGS 六名硬编码序 + 编译后 `scanLayoutBindingNames()` 全名 putIfAbsent 扫描对验
  （旧 resolvePhantomBindings/vboBinding/iboBinding 字段整体退役）;
- 捕获窗 = render() 内 createRenderPass 前张开 / finally 关闭, 窗首 clear;
- RenderPassUniformProbeMixin: @Inject TAIL ×2（slice/buffer 重载, buffer 重载包全范围 slice）。

### run22 结果（2026-09-17 深夜, 机制全绿 / 视觉红, 根因自爆）

标准档(无 env)。日志 /tmp/s1-t2-r22.log。
- **VVL 零消息**(全 log 仅 1 处 "validation" = 启动 "Enabling Vulkan validation layers" WARN):
  "映射本 stage 接口外 binding" 候选、"经典 push 冗余" 候选全部沉默 → 六映射全覆盖过 VVL 仲裁;
  且第 6 条映射(firstBinding=5)即使超出 layout 实际条目也未被点名(NVIDIA 5090 + VVL 1.4.341 合法实证)。
- 设备 gate PASSED; 堆 ready 229376B(table 131072 + reserved 96768, type4, writePath A, baseDev=0xe340000);
  首编在手术窗内成功("first compile ran under surgery"); 干净退出(descriptor heap closed, 零对象追踪)。
- **用户判定: 两面墙全不可见**(视觉 RED)。
- 自家金丝雀先叫: `run22 mapping-layout mismatch: declared [VBO IBO Globals DynamicTransforms Fog Projection]
  vs 实测 [VBO=0 IBO=1 Globals=null DynamicTransforms=3 Fog=4 Projection=2]`:
  ① Projection 实际在 2 号位(推断的 5 号位错);
  ② **Globals 不在合并 layout 里** —— shader 接口不引用它(与 VVL run21 只点名 b3/b4 + VBO/IBO 自洽:
     layout 实为 {VBO,IBO,Projection,DynamicTransforms,Fog} 五条目, Globals 条目被后端裁剪)。
- **根因(三证咬死)**: shader `gl_Position = ProjMat * ModelViewMat * pos`, ProjMat 读 **2 号绑定**;
  run22 每帧堆重写把 **Globals slice 写进 2 号槽(1,0)** → 投影矩阵 = Globals 缓冲的字节 →
  gl_Position 整片裁没 → 双墙同灭。DynamicTransforms(3)/Fog(4)/VBO(0)/IBO(1) 四槽全对。
  run19b 之所以看得见 = uniform 走驱动"经典状态残留"私道(2 号槽里是官方 push 的真投影);
  run22 全员走堆正门, 错座显形 —— **机制没坏, 是名标错了**。
- 四个 uniform 捕获窗零 WARN(Globals/DynamicTransforms/Fog/Projection 本帧 slice 全捕获)
  → 数据链时序健康, 与根因无关。

### run23 设计定稿（两名换位 + 全表 log, 零机制改动, 构建已绿）

1. `dhvkBindHeap` 换位: `writeUniformSlot(2, "Projection")` / `writeUniformSlot(5, "Globals")`(3/4 不动)
   → 2 号槽(1,0) 内容 = 官方 Projection ring slice → ProjMat 归位。静态映射按索引生成, 换位不碰映射字节。
2. `EXPECTED_BINDINGS` 重排为实测序 {VBO,IBO,Projection,DynamicTransforms,Fog,Globals}。
3. 扫描后新增 **全表 log**(`run23 layout binding table`, 按索引序排全部条目) → 若第 6 条目真带异名,
   下次一眼可见; 前 5 名保留 ERROR 红灯判据, 第 6 名(Globals)降为软验(run22 实测 null 不再判红)。
4. 预期: VVL 零维持(映射字节与 run22 完全相同); **墙 A 四色不漂移 + 墙 B 雾吞 = uniform 堆路径转绿**;
   干净退出。若墙仍不可见 → 头号嫌疑 = 全缓冲 BDA mixin(run23 前唯一全局因子), run24 关它做对照。

### run23 结果（2026-09-17 深夜, 机制全绿但墙仍不可见 → 换位不够, 另有病灶）

标准档, 日志 /tmp/s1-t2-r23.log。
- 日志侧全绿: `run23 layout binding table: [0=VBO 1=IBO 2=Projection 3=DynamicTransforms 4=Fog]`
  (五条目实锤, Globals 条目被后端裁剪); `full static mapping verified`(前 5 名硬验过, Globals 软验);
  VVL 零消息; 四 uniform 捕获零 WARN; 堆 229376B 干净退出, 零对象追踪。
- **用户判定: 仍什么都没看见, 加载转圈很久** → 视觉还是 RED。
- 推演收口: run19b(墙可见) 的 uniform 走经典 push 私道, 唯 VBO/IBO 走堆; run22/23 全员走堆后,
  即便 run23 把 Projection 换回 2 号槽, 字节层面与 run19b 经典态完全相同, 墙却消失
  → **差异只在"堆表传输"本身** → 嫌疑集中到堆表读取路径。

### run24 设计定稿（表宽算术修正 + 读回验证, 构建已绿）

**根因锁定(代码级): HeapLayout 按驱动 bufferDescriptorAlignment(8B) 铺槽, 而描述符实际
bufferDescriptorSize=16B → 表按 8B/槽 排布(131072B), 相邻 16B 描述符重叠 8B、size 字段互相覆盖:
VBO@0 与 IBO@8 互踩; run22/23 的 uniform 自 offset 16 起一路叠写, 每槽 size = 下一槽地址。
run19b 唯一被真实验证的堆读取 = VBO@offset 0(IBO 判别式贡献 ≈0, 坏了也看不出);
offset ≥8 的槽从未被证明。表区大小/预留区/映射 heapOffset 全部由该错误算术派生。**
1. `HeapLayout.slotStride(align, descSize)` = max(对齐, descSize) 再对齐取整(5090: 16B);
   `totalBytes/slotOffset` 改吃槽宽; DescriptorHeap 预算降级/写边界/ready log 同步
   (表 131072→262144B, 总 360448B, 预留区不变); `hostAddress()`/`slotStrideBytes()` 访问器。
2. **读回验证(run24 主裁决器)**: 前 5 帧 `logTableCheck` —— 六槽"期望 (addr,size)" vs
   主机映射实际字节(写路径 A coherent → 主机读回 = 设备视图), 全 MISMATCH 判红;
   附四 uniform 的 slice 元数据(buf 句柄/BDA/offset/len) + 首帧表头 128B 原始 hex 倒出。
3. 单测: `slotStrideNeverBelowDescriptorSize`(4 例: 8/16→16, 64/16→64, 8/8→8, 16/24→32)。
4. 预期: 若表宽即根因 → 墙 A/B 按 S0 验收显现, 六槽全 OK, VVL 零维持;
   若仍不可见 → 读回日志直接指出哪槽哪字节不对(或证明表字节全对 → 嫌疑转移到
   驱动 mapping 解释/slice 内容, run25 再按证据切"无矩阵探针 shader"或关 BDA mixin 对照)。

### run24 结果（表字节铁证全对, 墙仍不可见 → 表宽是隐患但非主因, 转"让 GPU 自报告"）

标准档, 日志 /tmp/s1-t2-r24.log。
- 堆 ready 360448B(table 262144 + reserved 96768, slotStride=16B) —— 表宽修正生效;
  VVL 零; 干净退出。
- **读回验证五帧全绿**: 六槽 host 读回与期望逐字节一致(VBO [0xafa0b00,128B]、IBO [..,24B]、
  Projection [0x9c815c0,64B]、DT ring 每帧滚动 [off 0/768B,192B]、Fog [40B]、Globals [56B]
  全 OK); 首帧 128B 原始 hex 与期望编码完全吻合; slice 元数据显示 DT/Fog ring 随 3-buffer
  轮换滚动、BDA 缓存命中, Projection/Globals 为持久缓冲。
- **用户判定: 仍无一墙可见** → 表里写的每字节都对, 驱动/着色器侧"读法"或矩阵内容另有问题。
- 关键认识: run19b 可见时 uniform 走经典 push 残留(驱动私道), 堆表映射读路径(尤其
  非几何 uniform 槽)从未被着色器真实读取验证过 → 不能再靠推断。

### run25 设计定稿（GPU 自报告探针, 构建已绿）

新 `far_terrain_probe` vsh/fsh + `PIPELINE_PROBE`(与 PIPELINE 完全同 BGL → 静态映射/手术/
捕获全复用; 唯一差异 = 探针 shader + 深度测试 ALWAYS_PASS 不写); `ACTIVE_PIPELINE`
常量 run25 指探针、run26 回 PIPELINE; ensureHeapSurgery 双管线同窗预编译。
探针语义: 裁剪不用任何矩阵 —— 墙 A 钉屏幕左(-0.55)、墙 B 钉右(+0.55), 近景 z=0,
关深度测试 → 只要管线/绘制/VBO 通, 双墙必然上屏; 颜色 = GPU 实读值自报告:
- **R** = |DT 矩阵行0|长度(健康刚体恒=1, 与朝向无关; 堆读归零 → 红通道黑)
- **G** = |Proj 行0|长度×0.5(健康≈0.7; 归零 → 绿通道黑)
- **B** = |VBO 头|/4000(墙 A≈0.1/墙 B≈0.5; 归零 → 蓝通道黑); 墙 B 整体 +0.5 提亮。
判读表: 左黄右白 = 三堆读全健康(则 run24 不可见 = 矩阵值把墙推出视锥, 转向矩阵内容/时序);
缺红 = DT 槽堆读归零; 缺绿 = Proj 槽; 缺蓝 = VBO 槽(则 run19b 经典路径结论要重审);
无墙 = 管线/绘制路径问题。表读回日志保留(前 5 帧)。

### run25 结果（机制全绿 / 用户"什么都没有" → 代码复审, 转双半屏对簿）

日志 /tmp/s1-t2-r25.log: 机制面全绿(device gate、surgery applied、layout 表
[0=VBO 1=IBO 2=Projection 3=DynamicTransforms 4=Fog]、full static mapping verified、
table check 5 帧 6/6 槽 OK、slice meta 健康、VVL 零、heap 360448B 干净关闭、
BUILD SUCCESSFUL; 客户端 61s 由用户看完后退出)。
视觉: 用户报"什么都没有" → 落 run25 解码表"无墙"分支。
但探针 gl_Position 不依赖任何矩阵 —— 即使三个堆槽全读零也应画出约 77×86px(1080p)
小色块, 什么都没看见 → 怀疑: ① 顶点输入退化(全顶点坍缩到同一 NDC 点 → 零面积三角形);
② 探针管线根本没被绘制(缓存/解析); ③ 像素没落到呈现面。

代码复审(全部对照 mc-src 反编译源码, 逐条排除):
1. **缓存键 = 对象身份**: `VulkanDevice.pipelineCache.computeIfAbsent(pipeline, ...)`;
   RenderPipeline 未重写 equals/hashCode → PIPELINE 与 PIPELINE_PROBE 是不同键,
   "共享 withLocation 去重"假设死亡; 探针 precompile 与 setPipeline 命中同一真对象。
2. **手术链完整**: 靶1 flags2 永生节点 pNext 仍链着 renderingInfo(颜色/深度格式链
   保留: depthAttachmentFormat=126=X8_Z24, 与 VVL 零点名的渲染通道兼容一致);
   靶2 命中 VS/FS 双 stage 的 pName(ByteBuffer); 靶3 layout(0L)。
   `withDepthPipeline()`/`withoutDepthPipeline()`: 深度态非空时只编 withDepth 变体
   (withoutDepth=0); 本 pass hasDepth=true → 绑 withDepth 变体, 恒有效。
3. **真实 layout 保留在 push-descriptor**: compile() 无条件创建真 VkPipelineLayout
   (record 字段), 手术只把 createInfo.layout 置 0。`drawIndexed → pushDescriptors()`
   → `vkCmdPushDescriptorSetKHR(cbu, 真layout, set0, writes)`。**实际 CBU 时序 =
   bindPipeline → bindVertex/Index → bindResourceHeap → pushDescriptorSet(堆之后!)
   → drawIndexed**: classic push 在堆绑定之后重新置位 set0(指向与堆表字节一致的
   官方 ring slice) → 无论按"最近状态优先"还是"堆静态映射优先"的语义, draw 时的
   uniform 数据都正确 → uniform 内容不是主嫌疑。VVL 零(push 合法)。
4. **混合态纸面矛盾**: `new BlendFunction(ZERO, ONE)` → `BlendEquation
   (sourceFactor=ZERO, destFactor=ONE, op=ADD)`(record 字段序 (src, dst, op) 定死)
   → srcColor=VK_BLEND_FACTOR_ZERO, dstColor=ONE → 结果 = dst = **纯背景(dst-pass)**,
   纸面上墙面颜色应被乘零。但此状态自 S0 首版提交(c2591d2)起未变, 而 S0/run19b
   墙面明明可见(四色验收) → 纸面数学与历史事实冲突 → 不纸面裁决, 让 GPU 同帧对簿。
5. cull=false(两条管线皆是); 顶点输入(POSITION_COLOR: loc0=Position vec3
   R32G32B32 @0, loc1=Color RGBA8 @12B, stride 16)与探针 `in vec3 Position;
   in vec4 Color;` 声明同构、与 VBO 字节布局一致; 探针 NDC 全在 |x|≤0.59,
   |y|≤0.08 内, 无裁剪。

### run26 设计定稿（双半屏仲裁, 构建已绿）

新 `far_terrain_probe2.vsh/fsh`: 接口空(不引用任何资源, 全量静态映射对其无害 =
run22-25 已实证形态), 不用任何矩阵; VS 按 (y,z) 符号把墙 A 侧钉**左半屏**
(cx∈[-1,0], cy∈[-0.5,0.5])画纯红, 墙 B 侧钉**右半屏**(cx∈[0,1])画纯青,
两半互不重叠。新管线 `PIPELINE_PROBE2_REPLACE`(ColorTargetState.DEFAULT = 不启用
混合 → 纯写入) / `PIPELINE_PROBE2_BLEND`(现用 (ZERO,ONE) 状态), 均 ALWAYS_PASS
深度 + cull off + 同 BGL。新缓冲 vboL/vboR(64B, putQuad 复用墙 A/B 侧四顶点)/
iboL/iboR(12B, 6 索引), dispose() 释放。
render() 改同 RenderPass 三连绘: ① 左半屏 PROBE2_REPLACE(VBO_L/IBO_L, 6 索引);
② 右半屏 PROBE2_BLEND(VBO_R/IBO_R, 6 索引); ③ run25 解码探针 PIPELINE_PROBE
(主 VBO/IBO, 12 索引, 中央小 quad 自报告堆读)。每次 draw 前 setPipeline(官方
置 anyDescriptorDirty → 重推 classic)+ dhvkBindHeap(重绑堆表)。
ensureHeapSurgery 同窗预编译 4 条管线。

判读表(用户只需报"左/右分别是什么颜色"):
- **左红 + 右青** = (ZERO,ONE) 在此后端上等效 replace, 绘制路径活着 → run25 小 quad
  是没被看见(尺寸/位置)→ run27 把解码探针放大到半屏级重跑, 同帧拿到堆读解码;
- **左红 + 右是场景(无青)** = (ZERO,ONE)=dst-pass 坐实 → run22-25 全灭根因 =
  我们自己的颜色目标状态(源色乘零) → run27 换 DEFAULT 收口(S0 可见悖论另查);
- **左是场景 + 右青** = 纯写入路径坏(意外) → 查 render pass/帧图;
- **左右都是场景** = 绘制路径整体不出图 → 查帧图/呈现。
- 附加: 若 ③ 中央小 quad 可见 → 其 RGB 即三堆槽解码(墙 A 左: R≈1, G≈0.35-0.5,
  B≈0.1; 墙 B 右: 整体 +0.5 提亮)。

### run26 结果（2026-09-18 凌晨, 双半屏裁决落定 → run22-25 全灭根因 = 颜色目标状态）

日志 /tmp/s1-t2-r26.log + 用户两张截图(左红右场景, 相机转动时红块屏幕锁定 = NDC 钉屏
属探针设计行为, 用户原话"一坨红色糊在我镜头上面不是固定的"):
- **左半屏(纯写入 DEFAULT)= 纯红, 像素级满屏半块(0-50% 宽 × 25-75% 高) → 绘制路径活着,
  纯写入状态在手术管线下正常工作; 右半屏((ZERO,ONE) 状态画纯青)= 场景原样透出,
  青被完全杀掉 → 纸面 dst-pass(src×0+dst×1) 被硬件裁决坐实; 中央 run25 解码 quad
  (同 (ZERO,ONE) 状态) 亦不可见, 三者自洽。
- **新 VVL 报错一类**(run22-25 没有, 全部来自 probe2 管线): `vkCmdPushDescriptorSetKHR():
  descriptorWriteCount must be greater than 0`(限流报满 10 次)。根因: probe2 shader
  接口完全空(不引用任何 set/binding 变量) → 后端合并 layout 条目数 = 0 →
  pushDescriptors 以 count=0 调 vkCmdPushDescriptorSetKHR → 违规。**经验: 探针 shader
  若想 VVL 零, 接口至少保留一个 phantom 绑定引用**(run25 probe 的 VS 引用 VBO/IBO/
  DT/Proj → 5 条目 → 无此问题)。run27 只绘主管线(5 条目), 此报错自然消失。
- **run22-25 全灭根因定案**: 主管线 (ZERO,ONE) 状态在 descriptor-heap(null layout)
  管线路径下按字面执行 = dst-pass → 源色乘零 → 墙像素 = 背景像素 → "表字节全对、
  VVL 全零、墙全灭" 完全解释。
- **S0/run19b 可见悖论的判读(5090 第三个驱动怪癖)**: 同一 (ZERO,ONE) 状态在 S0 时代
  与 run19b 的**经典管线**(真 layout、无 heap 位)下墙面可见(四色验收), 在本 arc 的
  **手术管线**(null layout + heap 位 + 静态映射)下按字面 dst-pass → 5090 驱动对
  classic 与 descriptor-heap 两条管线路径的混合方程处理不一致(经典路径疑似宽松/
  src-dst 互换, heap 路径字面执行)。与已录怪癖(经典状态在堆绑定后幸存、无 SDA 位
  BDA 返 0)同族: 5090 的 heap 快速路径对某些静态态按字面、经典路径另有一套。
  收口策略不依赖悖论裁决: **用对两条路径都无歧义的状态(DEFAULT = 不启用混合, 纯写入)**。

### run27 设计定稿（状态换 DEFAULT 纯写入, 主管线绘双墙, 构建已绿）

- `PIPELINE` / `PIPELINE_PROBE` / `PIPELINE_PROBE2_BLEND` 三处颜色目标状态全部换
  `ColorTargetState.DEFAULT`(blendFunction 缺席 → blendEnable=false → 纯写入,
  writeMask=15, RGBA8_UNORM) —— run26 左半屏已实证该状态在手术管线下像素级出图;
  BlendFunction/BlendFactor 导入随之删除(checkstyle 未用导入)。
- render() 回归单管线单绘制: setPipeline(PIPELINE) + 官方 uniform 捕获 +
  VBO/IBO 主缓冲 + 每帧堆表重写 + drawIndexed(12,1) —— 双墙(几何经堆源描述符、
  uniform 经每帧堆表)经 DEFAULT 状态出图。probe/probe2 管线转备用仲裁器(同窗预编译
  保留, 本帧不绘 → 0 条目 push 报错不再出现)。
- 预期(run27 绿线): 墙 A(-400, 雾带前)四色全显不位移 + 近处地形共享 reverse-Z 深度
  遮挡墙 A + 墙 B(-2000) 被 100% 官方雾吞没 + VVL 零(vs docs/baselines/) +
  干净退出(含 4 个 probe2 缓冲的 dispose 释放)。红 = 四色墙仍不可见(则转向
  矩阵内容/帧图时序) 或新 VVL 消息。

### run27 结果（机制全绿 + "没东西" → 未验证面暴露 + RD16 平凡嫌疑入案）

- 日志 /tmp/s1-t2-r27.log, 进程 19s(游戏内 ~9s, 用户关窗退出), 干净退出。
- **机制面全绿**: surgery 一行(4 管线同窗预编译) + 表回读 5 帧 × 6 槽全 OK
  (slotStride=16B, BDA/偏移逐帧跟踪 ring 滚动) + **VVL = 0**(run26 的 probe2
  空接口 0-count push 报错随单 draw 一并消失) + heap closed (360448B) + exit 0。
- **用户判读: "没东西"**(双墙不可见, 无红块 —— probe2 本帧不绘, 符合预期)。
- **关键推理 —— 从未被硬件验证的面**: run26 的红块只证明"顶点缓冲走堆"(经典
  `vkCmdBindVertexBuffers` 绑定 + NDC 钉死死坐标 + shader 零 UBO 引用)。而
  **"UBO 经静态堆映射读"六炉以来从未被验证**: 若 ProjMat/ModelViewMat 经堆表
  读零, `gl_Position = 0×…×pos = (0,0,0,0)` → w=0 → 全裁 → "表全对、VVL 全零、
  没东西"与 run27 症状**逐字吻合**。VBO phantom 读(binding 0 的堆描述符解引用)
  同样未验证(run26 probe2 接口为空, shader 不引用 binding 0/1; run25 探针本该
  验证它, 被 (ZERO,ONE) dst-pass 吞掉)。
- **新入案的平凡嫌疑(RD16 雾吞)**: 日志 `Changing view distance to 16, from 10`
  → 本 run 客户端 RD=16 → 官方雾距 end = RD·16 = **256 < 墙 A 400** →
  `fog_spherical/cylindrical_distance = length(worldPos)`(与相机无关, fog.glsl
  实测) → 墙 A **100% 官方雾**; 墙 B(-2000) 更在 RD16 远平面
  (depthFar = max(16·16·4, …) = **1024**)之外 → 被投影裁掉。∴ **完美管线在
  RD16 下也必"没东西"** —— 本阶段绿线(墙 A 雾带前)在 RD16 下天然不可满足,
  验收需 RD32(游戏内视频设置滑块拉满, 无代码改动; S0 可见性成立的条件即 RD32)。
- 两嫌疑(UBO fetch 读零 / RD16 雾吞)叠加且互不排斥 → run28 一炉并案裁决。

### run28 设计定稿（探针重武装 + 原始值自报告 + CPU 真值对拍, 构建已绿）

- **探针二绘**: render() 同帧两绘 —— 主管线双墙(几何堆源 + VBO 头瞬移金丝雀:
  RD32 下读零 → 墙 A 瞬移到镜头前 8 块, 巨型四色墙糊脸, 不可误判) +
  `PIPELINE_PROBE` 色板(NDC 钉死左/右半屏、深度恒过、**无雾**)。顶点/索引绑定
  沿主管线 CBU 状态, 堆状态沿用同一次 `dhvkBindHeap`; 探针 layout 与主管线同 BGL
  → setUniform 记录同名 slice → 其 drawIndexed 内的 pushDescriptors 推同款 classic
  描述符(5 条目, 非零, VVL 干净)。
- **探针 shader 升级原始值自报告**(替代 run25 长度版 —— 长度只能分零/非零):
  RGB = 0.5 + ModelViewMat 行0 分量(健康刚体行 ∈ [-1,1] → 精确 [0,1] 映射,
  **色相随相机旋转实时变化 = 活体金丝雀**; 读零 = 恒定 0.5 灰; 读错槽 =
  恒定怪异色); A = 0.5 + (VBO头 + IBO头)·0.001(phantom 金丝雀, 钉 OpVariable)。
- **CPU 真值日志**: 前 5 帧 log 传给 `writeTransform` 的 modelView 三行分量
  (joml 公共字段 m00..m22) → 与探针 GPU 解码(RGB)逐分量的对拍面。
- `frameCounter++` 自 `dhvkBindHeap` 移入 render() 顶部(每帧一增, 表检查与
  真值日志同帧号); 新增 `org.joml.Matrix4f` 导入。
- **现场指令(用户)**: 进世界后 Esc → 视频设置 → 渲染距离滑块**拉满(32)** →
  雾距 end=512 > 400, 墙 A 回到雾带前; 再看 -X 方向。
- **run28 裁决表**:
  | 探针色板表现 | CPU 真值 | 判读 | 下一步 |
  |---|---|---|---|
  | 色相随相机旋转实时变化 | 与 RGB(0.5+x) 一致 | UBO 静态映射抓取健康 → 红因 = RD16 雾吞 | RD32 后墙 A 仍缺 → 查帧图时序/主管线 draw 时序 |
  | 恒定 0.5 灰 | 健康(非零行) | 5090 静态映射 UBO fetch 读零 | run29: 全部 UBO 绑定 remap 到 offset 0(VBO 描述符)交叉检查 —— 现青(VBO 字节当矩阵)= 偏移错; 仍灰 = fetch 机制本身错(转 `vkWriteResourceDescriptorsEXT` 或 push-data 自序列化) |
  | 恒定怪异色(如青) | 健康 | 表偏移读错(错槽) | 重对 mapping heapOffset 语义(相对 heap 头 vs 相对 reserved 区) |
  | 色板全不现 | — | pass/帧图路径疑(与 run26 证据矛盾, 低概率) | 探针回 probe2 死坐标版 |

### run28 结果（探针现屏 = 恒定 0.5 灰 → UBO 静态堆映射读零坐实; 红因双确认）

- 日志 /tmp/s1-t2-r28.log, 进程 31s(游戏内 ~23s), 干净退出; 机制面再次全绿:
  surgery 一行 + 表回读 5 帧 × 6 槽全 OK + **VVL = 0** + heap closed。
- **CPU 真值日志首上线, 健康**: frame1 mv row0=(-0.629,-0.177,0.757),
  frame2-5 row0=(-0.560,-0.237,0.794)(相机稳定后) —— 写入 DT ring 的矩阵
  非零非单位, 主机侧内容无疑问。
- **用户截图裁决(双灰块)**: 左右两块 NDC 钉死色板**均现屏**(左 = 墙 A 侧
  cx∈[-0.95,-0.15], 右 = 墙 B 侧 —— Position 经经典顶点绑定读取正确,
  wallId 分裂符合设计), 但**两块同为恒定中灰 ≈ RGB(0.5,0.5,0.5)**。
  按 run28 解码(RGB = 0.5 + ModelViewMat 行0 分量): 灰 = 行0 三分量全 0
  → **GPU 经静态堆映射读 DT UBO = 零**(CPU 真值健康 → 主机侧内容已排除)。
  若走 classic push 通道则应得健康矩阵(同 slice 字节, S0 实证) → 推得
  **5090 在 null-layout 堆管线的绘制时刻选择堆通道, 且该通道 UBO 读零**。
- **裁决**: run22-27 七炉"没东西"的直接原因 = UBO 静态堆映射读零
  (run22-25 另有 (ZERO,ONE) dst-pass 叠加; run27 状态已修, 仅剩读零)。
  红因②(RD16 雾吞: 本 run 启动仍 RD16, "Changing view distance to 16")
  独立成立, 最终绿线仍需 RD32。
- **未决(机制学分叉)**: "读零"是 ① 5090 创建期烘焙 (addr,size) 不重读堆表,
  还是 ② 堆表 UBO fetch 路径失效, 还是 ③ 堆偏移整体解释错 —— run28 的灰
  与"创建时刻表内 Proj/DT/Fog/Globals 槽全零"完全同形, 无法区分 → run29 哨兵。

### run29 设计定稿（哨兵交叉判读: 创建期全表非零 + 每帧重写 + 三态解码）

- **哨兵缓冲**: 384B 设备缓冲(USAGE_UNIFORM|DEVICE_ADDRESS), 6 × 64B 槽,
  槽 i 的 UBO 行0.x = i+1(余 0) → BDA 2 代; `dispose` 释放。
- **时序设计(关键)**: `ensureBuffers`(先于 surgery)末尾把**六槽全部**重写为
  哨兵描述符 → 管线创建(ensureHeapSurgery 预编译 4 管线)时刻堆表**全非零且
  每槽可识别**; 之后每帧 `dhvkBindHeap` 把**六槽**(run29 起 VBO/IBO 亦每帧
  重写)重写为真实描述符(VBO/IBO → 墙缓冲, Proj/DT/Fog/Globals → 官方 slice),
  再绑整表 → 绘制时刻表内全真实值。
- **探针三态解码(shader 已改)**: R = clamp(DT行0.x × 0.25),
  G = clamp(Proj行0.x ÷ 3), B = clamp(VBO头 × 0.5), A = clamp(IBO头 × 0.5):
  | 绘制时刻 GPU 实际行为 | 色板 |
  |---|---|
  | 每帧活体重读(规范行为) | 暗色: R≤0.25, G≈0.27~0.43, B=0(VBO头 -400), A≈0.5 |
  | 创建期烘焙(停在哨兵) | 亮黄: R=1, G=1, B=0.5, A=1 |
  | fetch 路径失效(全零) | 全黑 |
  | 偏移整体错(全绑定读同槽) | 可识别的第四态(各通道 = 同一槽值解码) |
- **各分支的后续(裁决后立即执行)**:
  - 暗色(活体) → 堆表机制完全健康 → run27 灰应另有解释(回查: 本分支下
    run28 的灰只能来自创建期表零值被烘焙 —— 即与"亮黄"分支殊途同归, 需
    重审时序); 墙在 RD32 下应出图, 不出则查帧图时序。
  - 亮黄(烘焙) → 5090 创建期烘焙坐实 → **稳定描述符改造**: DT/Fog 换自有
    持久 staging 缓冲(192B/40B, 主机 coherent, 每帧 memcpy 官方 ring slice
    内容), 描述符 (addr,size) 一生不变 → 烘焙/活体双兼容; VBO/IBO/Proj/
    Globals 本就稳定无需动。机制仍是纯堆表, 无兜底。
  - 全黑(fetch 失效) → 静态映射 fetch 在 5090 对 UBO 不工作 → 转
    `vkWriteResourceDescriptorsEXT`(每帧经扩展写描述符进堆)或
    push-data 自序列化路线(用户裁决)。
  - 第四态(同槽) → 堆偏移解释整体错位 → 重对 heapOffset 语义(相对 heap 头
    vs 相对 reserved 区起点)再跑一轮。

### run29 结果（哨兵全黑 → 既非活体也非烘焙; 嫌疑收窄 + spec 复核）

- 日志 /tmp/s1-t2-r29.log, 进程 25s(游戏内 ~15s), 干净退出; 机制面全绿:
  sentinel armed 行 + surgery + 表回读 5 帧 × 6 槽全 OK(含 VBO/IBO 每帧重写)
  + **VVL = 0** + CPU 真值健康(row0=(-0.947,-0.103,0.305))。
- **用户截图裁决: 双块纯黑**("黑的")。按 run29 三态解码: 非亮黄(≠创建期烘焙)、
  非暗色(≠每帧活体) → **GPU 读到的描述符 = 零**: 驱动既没读创建时刻的哨兵表,
  也没读绘制时刻的真实表, 而是读到了"零描述符"((0,0) → 地址 0 → 读零)。
- **spec 复核(/tmp/dh-spec.txt)**: ① constantOffset 语义 = "offset 加到 heap 地址",
  offset = heapOffset + shaderIndex·stride —— 我们的解释与 spec 字面一致;
  ② bind 语义 = heapRange 全区间可用, reserved 为子区间 —— 我们的 bind(0, 整表,
  reserved 262144/96768) 合规; ③ **新发现 VUID-12350: UBO 描述符地址必须是
  minUniformBufferOffsetAlignment 的倍数** —— 实测官方 BDA 族 ≡ 192/128 mod 256
  (Proj 0x..5c0, DT ring 0x..dc0, Globals 0x..580), 而自有 VBO 基址 256 对齐:
  若 5090 该 limit = 256 且驱动硬执行, "官方缓冲 UBO 读零 + 自有缓冲健康"
  与 run29 全黑(含 VBO 头 -400 负值 clamp 到 0 的歧义)完全自洽 → 头号嫌疑。
- **run30 设计**: 探针换**无歧义长度自报告**(正交行长度恒 1 → 健康恒亮):
  品红(1,~0.5,1) = 全健康 / 纯蓝(0,0,1) = 仅自有对齐缓冲健康(对齐嫌疑坐实,
  转 staging) / 全黑 = fetch 整体死或偏移错位(run31 扫描)。+ 首帧 log 设备
  minUniformBufferOffsetAlignment/minStorage/maxUniformBufferRange + 六槽地址
  逐一对齐审计(addr % limit)。哨兵机制保留(无害, 备 run31 用)。

### run30 设计定稿（对齐/事实采集: limits 日志 + 地址审计 + 长度自报告）

- 探针解码(run30): R = |DT 行0|(健康恒 1 / 零 0), G = |Proj 行0|×0.5(健康
  ≈0.4~0.75), B = (|VBO头|+|IBO头|)/400(健康 ≈1.0, VBO 头 -400 → 1.0),
  A = 1。左右双块同色(共享 UBO 态)。
- Java: `run30LogDeviceFacts()`(render 首帧, VkHandles.pdevWrapper +
  VK10.vkGetPhysicalDeviceLimits) log minUniformBufferOffsetAlignment /
  minStorage / maxUniformBufferRange → 静态 `uboAlign`; `logTableCheck` frame1
  追加六槽描述符地址 % uboAlign 审计行(slice 缺失以 -1 占位)。
- 裁决: 纯蓝 + limit=256 + 审计非零 → **staging 方案**(DT/Fog/Proj 内容每帧
  memcpy 进自有 256 对齐 persistent 缓冲, 描述符 (base, len) 一生不变, 纯堆表
  无兜底); 品红 → 机制全通, 直取绿线(RD32); 全黑 → run31 payload/偏移扫描。

### run30 结果（对齐嫌疑阵亡 + 全黑复现 → 描述符位模式方言嫌疑升格）

- 日志 /tmp/s1-t2-r30.log(35s 游戏时间), 干净退出, **VVL = 0**; 启动即 RD16。
- **设备事实(5090)**: `minUniformBufferOffsetAlignment=64B`(不是 256!) +
  minStorage=16B + maxUniformBufferRange=64KB。
- **对齐审计(mod 64B): 六槽 BDA 全部 ≡ 0**(VBO 0xb581700, IBO 0xb581780,
  Proj 0x9c815c0, DT 0x7c40180, Fog 0x7c40940, Glob 0x9c81580) →
  **VUID-12350 嫌疑彻底阵亡**, 对齐不是根因。
- 表回读 6/6 OK(手写 [addr,size] 字节与 CPU 期望逐字节一致), CPU 真值健康。
- **用户截图裁决: 双块纯黑(同 run29)** → 连自有 256 对齐的 VBO/IBO 描述符
  也被读成零 → run28-30 的公共因子 = **描述符 payload 字节本身**。
- **spec 重读(决定性)**: `vkWriteResourceDescriptorsEXT(device, count,
  pResources(VkResourceDescriptorInfoEXT{type, data.pAddressRange}),
  pDescriptors(VkHostAddressRangeEXT{address, size}))` —— 描述符**位模式由
  实现生成(不透明)**, 驱动把位模式写进我们的表槽 host 地址; shader 访问时
  只读前 N 字节(N = vkGetPhysicalDeviceDescriptorSizeEXT, 5090 表槽宽 16B)。
  规格全文**无 "host-visible/coherent" 字样** —— 手写 [addr,size] 是
  descriptor_buffer 的方言, 不是 descriptor_heap 的。5090 驱动的方言
  ≠ [addr,size] → 我们的十六进制被驱动按自己的格式解码 → 读零/无效。
  自 run26 起堆表从未被驱动真正"看懂": 顶点/索引走经典
  vkCmdBindVertex/IndexBuffer(不经过表), 六路 UBO 全走表 → 全黑自洽。
- LWJGL 3.4.1 快照绑定核实(javap): `EXTDescriptorHeap.nvkWriteResourceDescriptorsEXT
  (device, int, long, long)` 裸指针版 / `vkWriteResourceDescriptorsEXT(device,
  Info$Buffer, HostRange$Buffer)` 数组版 / `vkGetPhysicalDeviceDescriptorSizeEXT
  (pdev, int)`; `VkDeviceAddressRangeEXT{address$(), size()}` /
  `VkHostAddressRangeEXT{address$(ByteBuffer), size()}`(无 sType);
  `VkResourceDescriptorInfoEXT{stype(字面量 1000135002, 官方头核实), type,
  data.pAddressRange}`; STYPE 类常量在此快照仍是半初始化 0 陷阱 → 恒用字面量。
  官方 2026 头(vulkan_core.h main) registry: RESOURCE_DESCRIPTOR_INFO=1000135002,
  BIND_HEAP_INFO=1000135003, SET_AND_BINDING_MAPPING=1000135005,
  SHADER_..._MAPPING_INFO=1000135006 —— 与我们既有字面量全部吻合。
- 5090 怪癖清单新增候选 #6: 驱动对堆表描述符按实现方言解码, 手写 [addr,size]
  不被识别(run31 双路对照裁决)。

### run31 设计定稿（双写路分槽对照: host 手写 vs 驱动亲笔）

- 规格正路 = 驱动把不透明描述符字节写进表槽(vkWriteResourceDescriptorsEXT);
  手写 [addr,size] 只是"可能恰好同方言"的赌注。run31 一次 run 内两条路并存:
  - **对照组(路径 A, host 手写 [addr,size])**: DT、Proj 两槽;
  - **实验组(路径 B, 驱动 nvkWriteResourceDescriptorsEXT 亲笔不透明字节)**:
    VBO、IBO、Fog、Globals 四槽(创建期哨兵/墙注册仍走 A, 保持 run29 语义)。
- 探针解码不变: R=DT(A), G=Proj(A), B=(|VBO头|+|IBO头|)/400(双 B)。
  裁决表: 品红(1,~0.5,1)=双路皆通 → 绿线; 纯蓝(0,0,1)=仅驱动亲笔可读 →
  run32 全六槽转 B 即正路; 粉(1,~0.5,0)=仅手写可读(反直觉, 查 B 槽写序);
  全黑 = 表 fetch 整体死(偏移基/堆范围) → run32 偏移扫描。
- 代码: `DescriptorHeap.writeBufferDescriptor(cell, slot, addr, size, viaApi)`
  新增 per-slot 覆写(旧签名委托 !writePathA, 创建期行为不变); 渲染器
  `writeUniformSlot(binding, name, viaApi)` 按上表分槽; `run30LogDeviceFacts`
  追加 `vkGetPhysicalDeviceDescriptorSizeEXT(UNIFORM_BUFFER)` 行(VUID-11207:
  槽宽 16B ≥ N 才合规); `checkSlot` 对 API 槽免严格比对、倒原始 hex 作指纹
  (规格: 相同输入 → 相同位模式, 跨 run 可对照)。
- 若 B 路胜出: 后续正路 = 全槽每帧经扩展写(或地址不变时写一次),
  手写路径退役; 哨兵/canary 保持休眠。机制仍纯堆表, 无兜底。

### run31 结果（双写路对照: 双路皆黑 → 方言破译但非元凶; 嫌疑收敛至堆表 fetch 的"取址/取处"）

- 日志 /tmp/s1-t2-r31.log, 干净退出, **VVL = 0**; 窗口内 RD32(地平线远)。
- **新设备事实: `descriptorSize(UNIFORM_BUFFER) = 8B`** —— 驱动写/ GPU 读**仅
  前 8 字节**。16B 手写 [addr,size] 只被读前半(=裸 BDA), 再按驱动方言解码 →
  对照组(DT/Proj)读零的机制精确落定。
- **驱动方言破译(表槽主机读回 = 驱动亲笔字节, 逐字节一致)**:
  opaque 8B = [低32位 = BDA>>6][高32位 = size/flags 码]。
  交叉验证全中: VBO 0xb581700>>6=0x2d605c ✓ / IBO 0xb581780>>6=0x2d605e ✓ /
  Fog 随 ring slice 逐帧旋转 0x7c40900/940/980>>6=0x1f1024/5/6 ✓ /
  Globals 0x9c81580>>6=0x272056 ✓。地址按 64B 粒度打包(与
  minUniformBufferOffsetAlignment=64B 同构)。高 32 位码值: VBO=0x40, IBO=0x10,
  Fog=0x18, Globals=0x20(精确语义 run32 需要时再解码)。
- **用户截图裁决: 双块全黑(R=G=B=0, 非预测的纯蓝)** → 驱动确实把自己的方言
  字节写进了表(读回 = 驱动字节), 但 GPU 静态映射 fetch **仍然读零** →
  "字节方言"只是半个故事(解释了对照组全黑), 两路共同的剩余因子在
  **fetch 的位置/路径**上:
  1. **offset-base 错位**: mapping 的 heapOffset 被驱动相对另一基址解释
     (heapRange base=0 vs reserved 区起点 262144 vs 堆缓冲 BDA 基址);
  2. **堆 range 语义反转**: 驱动把 reserved 区当成真正的"表"(app 区只算
     应用自己的 scratch) —— 我们的表在 offset 0 处, 恰在驱动视野外;
  3. **fetch 路径整体未实现**: 5090 对 null-layout + descriptor-heap-bit 管线的
     UBO 静态映射 fetch 是死路(顶点/索引走经典绑定不经表, 故 run26 红块幸存)。
- 预测复盘: 本炉预测"纯蓝"(仅 API 路健康) —— 猜对了"对照组必黑"
  (N=8B 的推论), 猜错了实验路 → 记录在案, 避免下次再押方言单边。
- **run32 设计(偏移/基址扫描)**: 双变体同炉 —— ① 全部 6 个 binding 的
  mapping heapOffset 一律指 0(=表头, VBO 槽的驱动字节), 探针改读"表头是否
  被 fetch 到"; ② 镜像变体: mapping 全指 reserved 区起点, 驱动字节经扩展写进
  reserved 区 → 若 ② 亮而 ① 黑, 坐实"表=reserved 区"的驱动语义; 两者皆黑 →
  fetch 死路, 转 vkCmdPushDataEXT 自序列化或 VVL 新档/全档, 交用户裁决。
  哨兵机制继续休眠; 手写 [addr,size] 路径自此降级为"对照组化石"。
