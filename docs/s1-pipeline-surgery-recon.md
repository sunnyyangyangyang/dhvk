# S1 管线侧 descriptor-heap 手术侦察报告（2026-09-16 深夜, 子代理产出）

官方树 = `mc-src/client/`（26.2 blaze3d vulkan backend, 反编译）；LWJGL = 3.4.1-snapshot（2026 值空间）。
所有 sType 字面量均 javap/运行时实测（本代 LWJGL 新生代扩展结构体的 `STYPE` 类常量 **= 0**，半初始化陷阱实锤 → 一律用字面量）。

## Q1 官方管线创建路径 = 经典 VkShaderModule

- SPIR-V 产出: `com/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule.java:219`
  `VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(this.spirv);` → vkCreateShaderModule（**不是** VkShaderEXT 新 shader-object API）。
- 调用链: `systems/GpuDevice.java:177 precompilePipeline` → `vulkan/VulkanDevice.java:256 precompilePipeline`（`pipelineCache.computeIfAbsent` → `compilePipeline:289`；shader 经 `glslCompiler.createIntermediary`/`compile`）→ **`VulkanDevice.java:306` `return VulkanRenderPipeline.compile(this, modules.layout(), pipeline, modules.vertex(), modules.fragment());`**
- 构造点: `vulkan/VulkanRenderPipeline.java:50` `public static VulkanRenderPipeline compile(...)`：
  - 管线布局（~L54）: `VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(layout.handle()))` → vkCreatePipelineLayout —— **单一 set layout = 传入 BGL 的 handle**。
  - shader stage（L67-74）: `VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(1).module(vertexModule).pName(nameMain)`（fragment 同, stage=16）—— **stage 的 pNext 从不设置**（无链）。
  - 管线创建（~L185-200）: `VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default().flags(0).pStages(...)...layout(pipelineLayout).pNext(renderingInfo)`；`renderingInfo = VkPipelineRenderingCreateInfoKHR`（动态渲染, depthAttachmentFormat=126，无深度版再改 0 重跑一次）；最终 `VK12.vkCreateGraphicsPipelines(device.vkDevice(), 0L, createInfo, null, pointer)`（~L198）。
- 管线级**没有** VulkanPNextStruct 之类的链机制（那是 VulkanBackend 设备创建专用的）；链 = 裸 pNext 指针。

## Q2 创建标志的构造点与 2026 字面量

- 现有拼法: 仅 `VkGraphicsPipelineCreateInfo.flags(0)`（VulkanRenderPipeline.java，见上）——32 位 `flags` 字段（javap: `public int flags()`）。
- `VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT = 68719476736l`（**long**, 2^36）→ org.lwjgl.vulkan.**EXTDescriptorHeap**（javap -constants）。放不进 32 位 flags 字段 → 必须经 **`VkPipelineCreateFlags2CreateInfo`**（存在, 另有 KHR 变体）pNext 节点传入；`VkGraphicsPipelineCreateInfo` 有类型化重载 `pNext(VkPipelineCreateFlags2CreateInfo)`（javap 确认）→ 手术点 = @Redirect 官方那句 `.pNext(renderingInfo)`（把 flags2 节点插在链头, pNext 指向 renderingInfo）。
- 该节点 sType 字面量: `VK14.VK_STRUCTURE_TYPE_PIPELINE_CREATE_FLAGS_2_CREATE_INFO = 1000470005`。
- `VK_SHADER_CREATE_DESCRIPTOR_HEAP_BIT_EXT = 1024`（int, EXTDescriptorHeap）。`VkShaderCreateInfoEXT.flags()` 为 **int**（非 uint64 值空间）。shader 位属于 VkShaderEXT（shader-object）路径；我们走经典 shader-module 路径，对应机制 = stage createInfo 的 mapping 链（规约: 挂 `VkPipelineShaderStageCreateInfo` pNext 即为"该 shader 的映射"）。

## Q3 layout 与 BGL 的关系

- BGL → set layout: `vulkan/VulkanBindGroupLayout.java:14-30` `create(...)`：逐 entry 生成 `VkDescriptorSetLayoutBinding.calloc(stack).descriptorType(UNIFORM_BUFFER→6 / SAMPLED_IMAGE→1 / TEXEL_BUFFER→4).descriptorCount(1).binding(i).stageFlags(17)`；`VkDescriptorSetLayoutCreateInfo...flags(1).pBindings(bindings)` → vkCreateDescriptorSetLayout。**set layout 的 bindings 与 BGL entries 一一对应（binding 号 = entry 下标 i），flags=1（push set）**。
- **layout = 该 BGL 的 set layout，一对一，不是多 BGL 并集**（VulkanRenderPipeline.java:54 单 handle 进 pSetLayouts）。
- 推论: ① shader 用了 set0/binding0 而 BGL 没有该 entry → layout 缺它 → 管线创建必失败。**layout 声明不了的 binding 比 BGL 多，除非把 pSetLayouts 重定向到一个自建（超集）set layout**（手术点: `VkPipelineLayoutCreateInfo.pSetLayouts(LongBuffer)` 的 INVOKE，或整段自建 set layout）。② 反之 layout 不会比 BGL 少。
- 现状对照: 我们的管线 BGL 已含 VBO/IBO 两个 phantom UNIFORM_BUFFER entry（layout 因此声明 set0 binding0/1）——静态映射落地后若想把 phantom 从 BGL 撤掉以停推送填充，必须同时自建超集 set layout，否则布局缺位。

## Q4 LWJGL 3.4.1 结构体/常量实测（jar: .../lwjgl-vulkan/3.4.1/d0601fdc.../lwjgl-vulkan-3.4.1.jar）

类全部存在: `VkShaderDescriptorSetAndBindingMappingInfoEXT` / `VkDescriptorSetAndBindingMappingEXT` / `VkDescriptorMappingSourceConstantOffsetEXT` / `VkDescriptorMappingSourceDataEXT`（联合体, 成员 constantOffset/pushIndex/...）/ `VkBindHeapInfoEXT` / `VkPipelineCreateFlags2CreateInfo(+KHR)`。

**STYPE 陷阱（运行时读类常量实测全为 0）**: 上述各扩展结构体 `STYPE` = 0 → sType$Default() 会写 0 → 一律字面量:

| sType 字面量 | 常量名 | 出处 |
|---|---|---|
| **1000135006** | VK_STRUCTURE_TYPE_SHADER_DESCRIPTOR_SET_AND_BINDING_MAPPING_INFO_EXT | EXTDescriptorHeap |
| 1000135005 | ..._DESCRIPTOR_SET_AND_BINDING_MAPPING_EXT | EXTDescriptorHeap |
| 1000135003 | ..._BIND_HEAP_INFO_EXT | EXTDescriptorHeap |
| 1000135004 | ..._PUSH_DATA_INFO_EXT | （参考） |
| **1000470005** | VK_STRUCTURE_TYPE_PIPELINE_CREATE_FLAGS_2_CREATE_INFO | VK14 |

- `VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_CONSTANT_OFFSET_EXT = 0`（EXTDescriptorHeap，源枚举从 0 起）。
- `VK_SPIRV_RESOURCE_TYPE_UNIFORM_BUFFER_BIT_EXT = 32`（VK14）——与我们现用 `resourceMask=0x20` 一致。
- `VkDescriptorMappingSourceDataEXT` 与 `VkDescriptorMappingSourceConstantOffsetEXT` **没有 sType/pNext 成员**（内联结构, 非链节点, 无需设 sType；前者是联合体: 走 constantOffset 分支）。
- `VkPhysicalDeviceDescriptorHeapPropertiesEXT` 有 **`long minResourceHeapReservedRange()`**（偏移常量 `MINRESOURCEHEAPRESERVEDRANGE`），另有 bufferDescriptorSize()/bufferDescriptorAlignment() 等 —— 保留区大小应动态查此字段（run19 实测 5090 驱动报 min=96768B）。
- `VkBindHeapInfoEXT` 2026 布局: sType/pNext(**必须 NULL**)/heapRange(VkDeviceAddressRangeEXT)/reservedRangeOffset(long)/reservedRangeSize(long) —— 保留区用**偏移**（相对 heapRange 起点）+大小，不再是地址对。
- `VkShaderDescriptorSetAndBindingMappingInfoEXT`: sType/pNext/mappingCount(int)/pMappings(数组指针)。`VkDescriptorSetAndBindingMappingEXT`: sType/pNext/descriptorSet(int)/firstBinding/bindingCount/resourceMask(int)/source(int)/sourceData(VkDescriptorMappingSourceDataEXT 内联)。

## Q5 管线侧手术缝（无官方 pNext 注入先例; 设备侧先例 = 我们的 VulkanBackendDeviceSurgeryMixin）

`VulkanRenderPipeline.compile` 是纯静态方法、栈上建所有结构 → 只能 @Redirect 其内部的 lwjgl 结构体 setter 调用（静态目标, 静态 handler; 与 run14 教训一致: 靶描述符+handler 返回类型须与靶方法签名一致）:

1. **管线 flags2 注入**: 靶 `VkGraphicsPipelineCreateInfo.pNext(Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;)Lorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo;`（类型化重载存在, 官方代码正是调它）→ handler 先建 `VkPipelineCreateFlags2CreateInfo`（sType=1000470005, flags2=68719476736, pNext=renderingInfo, **节点放 native heap 永生**——VVL 1.4.341 持 pNext 节点裸指针, run15/17 教训），再 `return ci.pNext(flags2Node)`。
2. **stage mapping 注入**: 靶 `VkPipelineShaderStageCreateInfo.pName(Lorg/lwjgl/system/Pointer;)Lorg/lwjgl/vulkan/VkPipelineShaderStageCreateInfo;`（VS/FS 各调一次, 同一 nameMain 指针）→ handler 里 `stage.pNext(ourMappingInfoNode)`（类型化重载 `pNext(VkShaderDescriptorSetAndBindingMappingInfoEXT)` 存在）后透传 `return stage.pName(name)`；mappingInfoNode = {sType=1000135006, mappingCount=2, pMappings=[vbo{descriptorSet=0,firstBinding=0,bindingCount=1,resourceMask=32,source=0,sourceData.constantOffset{heapOffset=表内偏移,heapArrayStride=0}}, ibo{...firstBinding=1...}]}，节点同样 native heap 永生（mappings 数组 + 每 mapping 节点 + sourceData 全是 VVL walker 会摸的内存）。
3. **门控**: compile() 对每条管线都会跑（且经 pipelineCache 缓存, 只跑一次）→ handler 需按"是否我们的远地形管线"过滤: 在 FarTerrainRenderer 调 precompilePipeline 前/后设/清 `DhVkClient` 的静态标志（与 surgeryApplied 同模式, render 线程内同步, 无竞态）。
4. 可选缝（若需超集 layout）: `VkPipelineLayoutCreateInfo.pSetLayouts(Ljava/nio/LongBuffer;...)` 的 INVOKE（官方: `pSetLayouts(stack.longs(layout.handle()))`）。
5. 失效路径注意: `VulkanDevice.compilePipeline` shader 编译失败时返回 INVALID 管线（VulkanDevice.java:296-304）——手术只在成功路径生效, 无需处理。
