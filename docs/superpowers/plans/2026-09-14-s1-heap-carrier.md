# S1 Implementation Plan: descriptor heap 载体（DH 几何搬运工）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"全体 cell 几何表 = 一个 descriptor heap，每帧一次 `vkCmdBindResourceHeapEXT`"在官方 MC 26.2 Vulkan 渲染器里跑通：设备手术 + 硬门槛、持久堆 + per-cell 堆源描述符、VRS 2×2、距离带 [官方雾起点, 官方 depthFar] 的全带流式机制（合成调试环承载验收），VVL 标准档 + 全开档双零。

**Architecture:** 官方 26.2 渲染器为唯一渲染引擎（S0 已验证的帧图 pass 模板）。本切片做三件事：① 设备手术——`VulkanBackend` 的扩展列表与 feature pNext 链上补 `VK_EXT_descriptor_heap`（条件式，不兜底）；② heap 载体——一段 GPU 内存作为描述符表（"堆"不是对象），per-cell 的 VBO/IBO 描述符 = 纯设备地址区间（arena 子区），mapping source = `HEAP_WITH_CONSTANT_OFFSET`，per-cell 偏移经 push constant 进 draw 参数；per-cell modelView 走官方 `writeChunkSections` 动态通道；③ 帧循环——cell 注册表（几何唯一权威）+ 高度面提取（DAG CPU 执行器）+ block tile 同步上传（R-b）+ 流式预算 + 合成调试环。

**Tech Stack:** Java 25 / fabric-loom 1.17.20（mod 脚手架）/ LWJGL 3.4.1（游戏随包，`org.lwjgl.vulkan.EXTDescriptorHeap` 绑定已核实）/ mixin 0.8.7 / glslang 16.2（shader 离线编译，`mod/scripts/compile-spirv.sh`）/ VVL 1.4.341（标准档 + `vvl-fullprofile` 全开档）/ RTX 5090（Vulkan 2026 代驱动 615.71）。

**Spec:** `docs/superpowers/specs/2026-09-14-s1-heap-carrier-design.md`（本计划的依据；执行者两份都读）

## Global Constraints

以下约束适用于**每一个任务**（值从 spec 原文照抄）：

- **基线裁决**：baseline = Vulkan 2026 里程碑 + `VK_EXT_descriptor_heap`，**不做旧设备兜底**；设备不满足 → mod 以明确日志自禁用（游戏照常运行），绝不静默降级。
- **S4 后路红线（全程不得违反，违反即返工）**：
  - R-a：cell 注册表/heap 表是几何唯一权威，渲染器只消费"cell → (几何子区, UBO)"，对几何来源无感知；
  - R-b：每 cell 的 32³ block-ID 量化 tile **从 S1 起同步上传** GPU（raw handle SSBO），禁止"先不传、S4 再补通道"；
  - R-c：heap 每 cell 子区容量按 S4 任意 mesh 最坏预算（16~64KB 量级）在 init 预摊，堆表不重建不搬家；
  - R-d：mesher 接口 = DAG（[block ready]→[提取]→[上传]→[表更新]），S1 执行器 = CPU，S4 只换执行器不换图；
  - R-e：顶点格式 = position（cell 原点相对坐标）+ color RGBA8，无光，**不变**；
  - R-f：任何 ABI 不得 bake"单层天空线"假设（高度场只是 S1 的内容，不是 ABI）。
- **VVL 全程开启**：每个任务的验收跑都带 `--vulkanValidation`；标准档 + 全开档（`VK_LAYER_SETTINGS_PATH`）零 validation / 零 object-tracking 消息是硬标准（对照 `docs/baselines/` vanilla 基线）。
- **距离带**：[官方雾起点, 官方 depthFar]，RD32 下 = [448, 2048] 块（S0 实测：RD 滑块 [2,32]，雾 end=RD·16、start=end−clamp(end/10,4,64)，depthFar=max(RD·16·4, cloudRange·16)）。测试一律 RD32。
- **流式预算**：帧建 ≤ 8 cells / 帧驱逐 ≤ 2 cells / 驻留上限 8192（LRU）；淡入时长 ≥ 官方 chunk fade（无 pop-in）。
- **性能**：RTX 5090 上 +1ms/帧以内（F3 + 简单计时）。
- **mixin 0.8.7 规则（S0 六轮踩坑沉淀，逐条照做）**：
  - 每个 `@Inject` handler 必须**以 `CallbackInfo` 参数收尾**（即使目标是 void）；
  - 构造器 `@At("HEAD")`（super() 之前）注入的 handler 必须 **static**；
  - `@Inject` 无 `args=@Capture`（要参数就用 `@Redirect`）；
  - `@Shadow` 目标不存在时给空体 `@Shadow` 方法兜底（S0 先例）。
- **关断纪律（S0 五轮修复沉淀）**：本 mod 自建的一切设备对象（buffer / memory / set layout / pipeline layout / pipeline / SSBO）必须在 `vkDestroyDevice` 之前释放——统一经 `FarTerrainRenderer.dispose()` 幂等路径，由 `RenderSystemShutdownMixin`（`shutdownRenderer` HEAD，DEVICE.close 之前）调用。每个新任务加的新对象都要进 `dispose()`。
- **shader 工具链**：`mod/scripts/compile-spirv.sh`（glslang 16.2），产物进 `mod/src/main/resources/assets/dhvk/shaders/core/`；改 shader 必重编译并确认 spirv 更新。
- **构建命令（沙箱内验证可用）**：
  ```bash
  cd /home/<user>/Documents/dh-vk2026
  GRADLE_USER_HOME=$PWD/.gradle-user-home JAVA_HOME=$PWD/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew :mod:build --offline
  ```
- **游戏启动（agent 启动，用户看屏）**：每次 `runClient` 启动前**先问用户**（用户在自己的屏幕上盯窗口）。
  ```bash
  cd /home/<user>/Documents/dh-vk2026
  GRADLE_USER_HOME=$PWD/.gradle-user-home JAVA_HOME=$PWD/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2 \
  VK_LAYER_PATH=$PWD/mc-src/.vanilla-run/vulkan-layers \
  VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile \
  ./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation" 2>&1 | tee /tmp/s1-run-<标签>-<HHMMSS>.log
  ```
  （后台 job 运行；标准档 = 去掉 `VK_LAYER_SETTINGS_PATH` 一行。全开档 = 保留。用户说"没问题/好"= 目视验收通过。）
- **VVL 零报错核验**（每次 run 后对 tee 日志）：
  ```bash
  grep -c "Validation" /tmp/s1-run-*.log   # 期望 0（或仅与 vanilla 基线同款的既有噪声）
  grep -n "object tracking\|has not been destroyed\|Unrecognized" /tmp/s1-run-*.log
  diff <(grep -E "^\[[0-9:]+\] \[[A-Z]+/[A-Z]+\]" /tmp/s1-run-*.log | sed -E 's/^\[[0-9:]+\] //' ) \
       <(grep -E "^\[[0-9:]+\] \[[A-Z]+/[A-Z]+\]" docs/baselines/<对应vanilla日志> | sed -E 's/^\[[0-9:]+\] //')
  ```
- **代码读码笔记契约**：任务 0 产出的 `docs/s1-code-reading-notes.md` 是后续任务的事实来源；后续任务凡标"以笔记 §N 为准"的步骤，执行者先读笔记再写码，笔记与代码冲突时**以笔记为准**（笔记是实测结论）。

---

## File Structure（本计划创建/修改的文件与职责）

**新建（mod/src/main/java/dev/dhvk/）：**

| 文件 | 职责（单一） |
|---|---|
| `mixin/VulkanBackendDeviceSurgeryMixin.java` | 设备手术：扩展列表 + feature pNext 链补 `VK_EXT_descriptor_heap`（条件式） |
| `heap/DescriptorHeap.java` | 堆内存生命周期 + 描述符槽子分配 + 描述符写入 + `vkCmdBindResourceHeapEXT`（raw API 薄封装） |
| `heap/HeapLayout.java` | 纯 Java 的槽位算术（子分配/对齐/容量）——可单测，无 native 依赖 |
| `GeometryArena.java` | VBO/IBO 数据 arena（官方 `GpuBuffer` 或 raw buffer，per-cell 子区，增量上传） |
| `CellGeometryTable.java` | cell → 描述符表偏移 的映射 + dirty 描述符重写 |
| `CellRegistry.java` | cell 注册表（几何唯一权威）：驻留/脏集/帧建/驱逐/LRU/剔除（纯逻辑，可单测） |
| `HeightfieldExtractor.java` | DAG 阶段"提取"的 CPU 执行器：已加载 chunk → 每列顶面实方块 → verts/indices/colors |
| `BlockTileUploader.java` | R-b：每 cell 32³ block-ID 量化 tile → raw SSBO arena 上传 |
| `SyntheticRing.java` | 合成调试环：全带确定性合成高度场生成器 + F3+K 开关 |
| `mixin/VulkanCommandEncoderProbeMixin.java`（若任务 0 笔记 #2 判定需要） | 捕获官方 command encoder 的 raw `VkCommandBuffer` |

**修改：**

| 文件 | 改动 |
|---|---|
| `DhVkClient.java` | + 设备硬门槛检查（`descriptorHeap` feature）+ 禁用路径 + 手术开关（env `DHVK_NOSURGERY`） |
| `FarTerrainRenderer.java` | S0 双墙 → S1 帧循环（visible set → 堆绑定 → per-cell draw）；`dispose()` 扩全量有序释放 |
| `VkHandles.java` | + `physicalDevice` 字段；`capture()` 加确认日志（任务 0） |
| `mixin/VkHandlesProbeMixin.java` | 捕获面扩到 `vkPhysicalDevice`（任务 0） |
| `resources/assets/dhvk/shaders/core/far_terrain.vsh/.fsh` | + per-cell modelView 通道（ChunkSection 同源机制）+ 2026 式 VBO 描述符顶点输入（语法以笔记 §1 为准） |
| `resources/dhvk.mixins.json` | 注册新 mixin |
| `mod/build.gradle` | + JUnit（`testImplementation`）+ `test { useJUnitPlatform() }`（任务 4） |

**文档/测试：**

| 文件 | 职责 |
|---|---|
| `docs/s1-code-reading-notes.md` | 任务 0：六项读码先决的实测结论（后续任务的事实来源） |
| `mod/src/test/java/dev/dhvk/CellRegistryTest.java` | 注册表预算/LRU/驻留上限 单测 |
| `mod/src/test/java/dev/dhvk/HeapLayoutTest.java` | 描述符槽子分配算术 单测 |
| `docs/s1-acceptance/` | 任务 6：验收记录 + 日志 + 截图归档 |

---

### Task 0: 读码先决 + 探针修复（六项勘察 + 确认日志）

**Files:**
- Create: `docs/s1-code-reading-notes.md`
- Modify: `mod/src/main/java/dev/dhvk/VkHandles.java`
- Modify: `mod/src/main/java/dev/dhvk/mixin/VkHandlesProbeMixin.java`
- Read: `mc-src/client/com/mojang/blaze3d/` 下 `vulkan/VulkanBackend.java`、`vulkan/VulkanCommandEncoder.java`（或 `GpuDevice.java` 的 encoder 面）、`pipeline/VulkanRenderPipeline.java`、`pipeline/BindGroupLayouts.java`、`textures/...`/`systems/RenderPass` 的 `createRenderPass` 实现；`mc-src/artifacts/client.jar` 内 `assets/minecraft/shaders/core/terrain.vsh(.spirv)`；`refs/vulkan-headers/registry/vk.xml`；`refs/vulkan-headers/include/vulkan/vulkan_core.h`

**Interfaces:**
- Produces: `docs/s1-code-reading-notes.md`（§1~§6 六节 + §7 探针结论，每节 = 读了什么(文件:行) / 发现 / **实施决定**（含后续任务要用的精确调用形态））；`VkHandles.capture(long device, long queue, long commandPool, long physicalDevice)` + `VkHandles.physicalDevice`

- [ ] **Step 1: 建笔记骨架**

写 `docs/s1-code-reading-notes.md`，头部 + 六节空标题（§1 顶点输入 ABI 与堆源 BGL 声明 / §2 encoder raw CBU / §3 堆内存写入路径与 5090 memory type / §4 GpuBuffer 设备地址 / §5 VRS 2026 core 形态与 pipeline pNext 注入点 / §6 VkBindHeapInfoEXT reservedRange 语义 / §7 探针状态），每节预留"发现 / 实施决定"两个子段。

- [ ] **Step 2: 读码 #1——顶点输入 ABI 与堆源 BGL 声明**

依次读：
1. `mc-src/artifacts/client.jar` 解出 `assets/minecraft/shaders/core/terrain.vsh`（GLSL）与 `terrain.vsh.spirv`（若有）；GLSL 的顶点输入声明照抄进笔记；`spirv-dis`/`glslangValidator --aml`（宿主已装 glslang 16.2）反汇编 .spv，确认顶点输入是经典 `OpVariable` Input（location 绑定）还是 descriptor/buffer-reference 形态；
2. `mc-src/client/.../pipeline/RenderPipeline.java` 的 `Builder` 与 `BindGroupLayout` 数据面：`withBindGroupLayout` 接受什么类型、set layout 的 binding 如何描述（能否表达 mapping source）；
3. `VulkanRenderPipeline.java`：set layout / pipeline layout 的创建代码，找 `pNext` 链的构建点（`VkShaderDescriptorSetAndBindingMappingInfoEXT` 挂哪）；
4. `refs/vulkan-headers/registry/vk.xml` 里 `VK_EXT_descriptor_heap` 章节的 spec 文本：堆源 VBO/IBO 绑定的规范形态（mapping source 与顶点输入状态的关系）；
5. 官方 `VulkanRenderPass.setVertexBuffer/setIndexBuffer`（`vulkan/VulkanRenderPass.java:173-188`，已知走经典 `VK12.vkCmdBindVertexBuffers/vkCmdBindIndexBuffer`）——记录"官方自身走经典通道"这一事实（R2 退路的模板来源）。

**实施决定**写进笔记 §1：(a) 我们 pipeline 的几何 BGL 如何声明（BGL 构造代码骨架 + `VkDescriptorSetAndBindingMappingEXT` 挂接点）；(b) shader 顶点输入 GLSL 语法（给任务 2 直接抄）；(c) 裁决：经典 `OpVariable Input`+IA 重定向 vs BDA 拉取式（设计倾向前者，以 spec 文本终裁）；(d) 若走 raw 自建 pipeline（§5 判定），记录需要复刻的 `VkGraphicsPipelineCreateInfo` 全部字段值。

- [ ] **Step 3: 读码 #2——encoder raw CBU**

读 `GpuDevice.createCommandEncoder` 与 `VulkanCommandEncoder`（或 26.2 对应类）：字段面里有没有 raw `VkCommandBuffer` 可直接取（`@Shadow` 即可）？pass 录制时的 commandBuffer 何时可用（`createRenderPass` 前后）？
**实施决定**：绑定发射点代码骨架（在 `FarTerrainRenderer.render()` 的哪个语句后调 `EXTDescriptorHeap.vkCmdBindResourceHeapEXT`）；若字段取不到，决定探针 mixin 的 @Shadow/@Redirect 形态（笔记给 `VulkanCommandEncoderProbeMixin` 的完整代码）。

- [ ] **Step 4: 读码 #3——堆内存写入路径 + 5090 memory type**

读 `vk.xml` 的 `vkWriteResourceDescriptorsEXT` 与 `VkHostAddressRangeEXT`/`VkBindHeapInfoEXT` 语义段落：`pDescriptors`（host 地址区间）是"写入目标"还是"payload 来源"？驱动是否要求目标内存 host-visible？
然后查 5090 的 memory types（两种途径取一：a. 任务 2 的 init 代码里加一次性 memory-properties dump 日志；b. 用 `vkcube`/`vkconfig` 现成输出——若宿主没有就用 a）。
**实施决定**：堆内存分配代码骨架（`vkGetPhysicalDeviceMemoryProperties` 选 type 的判据：优先 `DEVICE_LOCAL|HOST_VISIBLE`（ReBAR 窗口），查 `HOST_COHERENT` 标志定写后是否 `vkFlushMappedMemoryRanges`；纯 device-local → staging 垫底）+ 描述符写入代码骨架（`vkWriteResourceDescriptorsEXT` 调用 or CPU 直写映射）。

- [ ] **Step 5: 读码 #4——GpuBuffer 设备地址暴露**

读 `GpuBuffer.java`/`VulkanGpuBuffer.java`：`vkBuffer()` 之外有没有 `address()`/`deviceAddress`（`vkGetBufferDeviceAddress` 结果）？`GpuDevice.createBuffer` 的 usage flag 全表（有没有 STORAGE）？
**实施决定**：arena 的地址区间描述符怎么取 `deviceAddress`（官方暴露则直接用；否则 raw `vkCreateBuffer+vkBindBufferMemory+vkGetBufferDeviceAddress` 的完整代码骨架，含 memory type 选择——与 §4 共用）。

- [ ] **Step 6: 读码 #5——VRS 2026 core 形态 + pipeline pNext 注入点**

`refs/vulkan-headers/registry/vk.xml` + `vulkan_core.h`：1.4.357 里 VRS 相关结构体（已知有 NV 形态 `VkPipelineCoverageModulationStateCreateInfoNV`；查 2026-core 形态——若 core 化则有 `VK_STRUCTURE_TYPE_PIPELINE_VARIABLE_RATE_STATE_CREATE_INFO` 类结构体，grep "VARIABLE_RATE\|COVERAGE_MODULATION"）；再读 `VulkanRenderPipeline.java` 的 `vkCreateGraphicsPipelines` 调用点：`pNext` 链上我们能不能挂（官方 pNext 已占哪些位、是否走 `VulkanPNextStruct` 机制）。
**实施决定**：VRS 2×2 的声明代码骨架（官方 pNext 可挂 → 走官方 pipeline 创建；不可挂 → raw 自建 pipeline 完整代码骨架，含笔记 Step 2(d) 记录的复刻字段 + "render pass 本体仍走官方 createRenderPass"）。

- [ ] **Step 7: 读码 #6——reservedRange 语义**

`vk.xml` 查 `VkBindHeapInfoEXT.reservedRangeOffset/reservedRangeSize` 的 spec 文本（保留区间给谁用、S1 传 0 是否合法）。
**实施决定**：`bind()` 的 bindInfo 参数定稿。

- [ ] **Step 8: 探针修复——capture 确认日志 + physicalDevice 捕获**

`VkHandles.java` 改为：

```java
package dev.dhvk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VkHandles {

    private static final Logger LOGGER = LoggerFactory.getLogger(VkHandles.class);

    public static long device = 0L;
    public static long queue = 0L;
    public static long commandPool = 0L;
    public static long physicalDevice = 0L;
    public static boolean captured = false;

    private VkHandles() {
    }

    public static void capture(long deviceHandle, long queueHandle, long commandPoolHandle, long physicalDeviceHandle) {
        device = deviceHandle;
        queue = queueHandle;
        commandPool = commandPoolHandle;
        physicalDevice = physicalDeviceHandle;
        captured = true;
        // S0 悬案收尾: 捕获成功必须留下日志(此前"not captured"只出现在设备创建之前的启动时刻)
        LOGGER.info("[dhvk] vk handles captured: {}", format());
    }

    public static String format() {
        if (!captured) {
            return "not captured yet (probe mixin not fired)";
        }
        return String.format("device=0x%x queue=0x%x pool=0x%x pdev=0x%x",
                device, queue, commandPool, physicalDevice);
    }
}
```

`VkHandlesProbeMixin.java` 的 handler 扩为四参（加 `@Shadow private VkPhysicalDevice vkPhysicalDevice;`）：

```java
    @Shadow
    private VkPhysicalDevice vkPhysicalDevice;

    @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
    @Inject(method = "<init>", at = @At("RETURN"))
    private void dhvkCaptureVkHandles(CallbackInfo ci) {
        VkHandles.capture(this.vkDevice.address(), this.graphicsQueue.vkQueue().address(),
                0L, this.vkPhysicalDevice != null ? this.vkPhysicalDevice.address() : 0L);
    }
```

- [ ] **Step 9: 构建 + 首跑验证探针**

Run: 构建命令（Global Constraints）+ 标准档 runClient（**先问用户**）。
Expected: 日志出现 `[dhvk] vk handles captured: device=0x... queue=0x... pool=0x0 pdev=0x...`（pool 仍为 0 占位，S1 暂不用 command pool）；VVL 零新增报错。若仍 "not captured" → 按 spec §7 R6 重新布探针（改挂 `VulkanDevice` 构造 HEAD + 字段读取，或 `RenderSystem.getDevice()` 首次调用点），修到日志出现为止。

- [ ] **Step 10: 补全笔记 + 提交**

笔记 §1~§7 全部写完（每节有"实施决定"），`git add docs/s1-code-reading-notes.md mod/src/main/java/dev/dhvk/VkHandles.java mod/src/main/java/dev/dhvk/mixin/VkHandlesProbeMixin.java && git commit -m "S1 任务0: 六项读码先决勘察 + 探针确认日志/physicalDevice 捕获"`。

---

### Task 1: 设备手术（扩展 + feature pNext）+ 硬门槛

**Files:**
- Create: `mod/src/main/java/dev/dhvk/mixin/VulkanBackendDeviceSurgeryMixin.java`
- Modify: `mod/src/main/java/dev/dhvk/DhVkClient.java`
- Modify: `mod/src/main/resources/dhvk.mixins.json`（client 列表加 `VulkanBackendDeviceSurgeryMixin`）

**Interfaces:**
- Consumes: 笔记 §2/§5 的设备创建钩点结论；`VkHandles`（任务 0）
- Produces: `DhVkClient.gatePassed()`（boolean，硬门槛结果）+ `DhVkClient.disabled()`（mod 是否自禁用）——任务 2~5 的一切初始化都先查这两个

- [ ] **Step 1: 读笔记 + 定钩点**

读笔记中任务 0 对 `VulkanBackend.createDevice`（`mc-src/client/com/mojang/blaze3d/vulkan/VulkanBackend.java` L55 静态扩展集 / L160-161 multi_draw 条件 / L422-432 扩展 buffer 构建 / L405-430 feature pNext 链）的钩点结论。钩点形态二选一（以笔记判定为准）：
- 形态 A：`@Inject` 到本地扩展列表构建之后（`@At` 对准 multi_draw 条件分支之后、`enabledExtensionsBuffer` 构建之前的语句）；
- 形态 B：`@Redirect` 替换 multi_draw 条件判断方法 / 列表填充方法，在其尾部追加。

- [ ] **Step 2: 写设备手术 mixin**

```java
package dev.dhvk.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.dhvk.DhVkClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S1 设备手术①(扩展)+②(feature pNext):给官方设备补 VK_EXT_descriptor_heap。
 * 条件式——物理设备声明了该扩展才加(官方 multi_draw L160 同款姿势):
 * 驱动没有 → 不加 → 设备创建照常成功 → DhVkClient 硬门槛报禁用(不兜底, 游戏不崩)。
 * 钩点形态以 docs/s1-code-reading-notes.md §2 结论为准; 下方为默认形态(A)。
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendDeviceSurgeryMixin {

    // 形态 A: 注在 createDevice 的扩展列表填充完成点(@At 目标 = 笔记 §2 钉死的语句)
    @SuppressWarnings("UnusedVariable")
    @Inject(method = "createDevice", at = @At(value = "INVOKE",
            target = "org/lwjgl/system/PointerStack$ThreadLocalStack.callocPointer(I)Lorg/lwjgl/system/PointerBuffer;",
            shift = At.Shift.AFTER))
    private void dhvkAddDescriptorHeap(long window, Object defaultShaderSource, Object debugOptions,
            Runnable criticalShaderLoader, CallbackInfo ci) {
        if (!DhVkClient.surgeryEnabled()) {
            return; // 负门槛测试 build(DHVK_NOSURGERY=1)跳过手术
        }
        // 1) 扩展: 物理设备声明 VK_EXT_descriptor_heap 才加
        //    (VulkanPhysicalDevice.hasDeviceExtension —— 局部变量按笔记 §2 的取法;
        //     形态 B 时改在 Redirect 体内追加 "VK_EXT_descriptor_heap")
        // 2) feature pNext: 把 VkPhysicalDeviceDescriptorHeapFeaturesEXT(descriptorHeap=true)
        //    挂进官方 VulkanPNextStruct 链(创建路径 L430 deviceCreateInfo.pNext 之前),
        //    查询路径(vkGetPhysicalDeviceFeatures2)同步挂——两处共用笔记 §2 的链构建点。
        //    结构体: VkPhysicalDeviceDescriptorHeapFeaturesEXT.calloc(stack)
        //            .sType(1000135009 /* VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT */)
        //            .descriptorHeap(true)
        //    LWJGL 3.4.1 随包已核实(该结构体 + $Buffer 变体均在 jar 内)。
        // 详细钩子代码以笔记为准; 本类只含手术, 不做任何 fallback。
        // 3) 日志: LOGGER.info("[dhvk] device surgery applied: VK_EXT_descriptor_heap + descriptorHeap feature")
    }
}
```

⚠️ 执行者注意：上面 `@At` 的 `target`/`shift` 与 handler 体是**默认形态的骨架**，必须按笔记 §2 的实测钩点改写（mixin 钩点错了会 `InvalidInjectionException`，S0 六轮血泪）；`VulkanBackend.createDevice` 的四个参数名以反混淆树为准（`window` / `defaultShaderSource` / `debugOptions` / `criticalShaderLoader`，见 `VulkanBackend.java` 的 `createDevice` 签名）。feature pNext 若笔记判定需独立 mixin（链构建是独立静态方法），则拆出 `VulkanBackendFeatureChainMixin`，同样注册进 `dhvk.mixins.json`。

- [ ] **Step 3: DhVkClient 硬门槛**

`DhVkClient.java` 增加（设备就绪后调用——挂载点 = S0 已验证的 entrypoint 设备初始化时机）：

```java
    /** 负门槛测试开关: runClient 前 export DHVK_NOSURGERY=1 即跳过设备手术(验证"不兜底")。 */
    public static boolean surgeryEnabled() {
        return !"1".equals(System.getenv("DHVK_NOSURGERY"));
    }

    /** 硬门槛: descriptorHeap feature 未置位 → mod 自禁用(明确日志, 游戏照常跑)。 */
    public static void checkDeviceGate() {
        if (!VkHandles.captured || VkHandles.physicalDevice == 0L) {
            LOGGER.error("[dhvk] device gate FAILED: vk handles not captured -> mod disabled");
            disabled = true;
            return;
        }
        // 用捕获的 physicalDevice 自查 feature(pNext 挂 VkPhysicalDeviceDescriptorHeapFeaturesEXT)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceDescriptorHeapFeaturesEXT feat = VkPhysicalDeviceDescriptorHeapFeaturesEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT /* 1000135009 */);
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            f2.pNext(feat);
            VK12.vkGetPhysicalDeviceFeatures2(VkHandles.physicalDevice, f2);
            if (feat.descriptorHeap() != 0) {
                LOGGER.info("[dhvk] device gate PASSED: descriptorHeap feature enabled");
            } else {
                LOGGER.error("[dhvk] device gate FAILED: descriptorHeap feature not enabled -> mod disabled, vanilla renderer running");
                disabled = true;
            }
        }
    }
```

（`disabled` 为 `public static volatile boolean`；`VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT` 若 LWJGL 常量缺失则用字面量 `1000135003`/`1000135009`——1.4.357 头已核实值。`VK12.vkGetPhysicalDeviceFeatures2` 的 `VkPhysicalDevice` 参数是 `long` 句柄——按 LWJGL 签名适配。）

- [ ] **Step 4: 注册 mixin + 构建**

`dhvk.mixins.json` 的 `client` 数组加 `"VulkanBackendDeviceSurgeryMixin"`（若拆了 feature mixin 则一并加）。Run: 构建命令。Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 正路径验证（标准档 runClient，先问用户）**

Run: 标准档 runClient（Global Constraints 的启动命令，去掉 `VK_LAYER_SETTINGS_PATH`）。
Expected: 日志出现 `[dhvk] device surgery applied: ...` 与 `[dhvk] device gate PASSED: descriptorHeap feature enabled`；S0 双墙仍正常出图（用户目视）；VVL 零新增报错、干净退出（VVL 核验命令）。

- [ ] **Step 6: 负路径验证（DHVK_NOSURGERY=1 runClient，先问用户）**

Run: `DHVK_NOSURGERY=1` + 标准档 runClient。
Expected: 日志出现 `[dhvk] device gate FAILED: descriptorHeap feature not enabled -> mod disabled, vanilla renderer running`；**无** "device surgery applied"；S0 双墙不出现（mod 禁用 = 帧图不挂 pass）；VVL 零报错、退出 0（游戏干净跑 vanilla 渲染器）。

- [ ] **Step 7: 提交**

```bash
git add mod/src/main/java mod/src/main/resources/dhvk.mixins.json
git commit -m "S1 任务1: 设备手术(VK_EXT_descriptor_heap 扩展+feature pNext, 条件式) + 硬门槛(不兜底) + DHVK_NOSURGERY 负路径"
```

---

### Task 2: descriptor heap 核心（内存 + 子分配 + 写 + 绑）+ 双墙改走堆

**Files:**
- Create: `mod/src/main/java/dev/dhvk/heap/HeapLayout.java`
- Create: `mod/src/main/java/dev/dhvk/heap/DescriptorHeap.java`
- Modify: `mod/src/main/java/dev/dhvk/FarTerrainRenderer.java`（双墙的 VBO/IBO 改经堆源描述符；`dispose()` 加 heap 释放）
- Modify: `mod/src/main/resources/assets/dhvk/shaders/core/far_terrain.vsh`（顶点输入改笔记 §1(b) 的堆源语法）+ 重编译 spirv

**Interfaces:**
- Consumes: 笔记 §1（BGL 声明 + shader 语法）、§2（CBU 发射点）、§3（内存分配 + 写入骨架）、§4（arena 地址——本任务 arena = 复用 S0 的 `GpuBuffer` vbo/ibo，只读其设备地址）、§6（bindInfo 参数）
- Produces: `DescriptorHeap`（`init(long cellCapacity)` / `long slotOffsetOf(int cell)` / `writeBufferDescriptor(int cell, boolean vbo, long deviceAddress, long sizeBytes)` / `void bind(long commandBuffer, long offset, long sizeBytes)` / `close()`）；`HeapLayout`（纯算术：`long slotOffset(int cell, int slotsPerCell, long slotAlignment)` / `long totalBytes(long cellCapacity, int slotsPerCell, long slotAlignment)`）

- [ ] **Step 1: HeapLayout（纯 Java，先写测试——TDD）**

`mod/src/test/java/dev/dhvk/HeapLayoutTest.java`：

```java
package dev.dhvk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeapLayoutTest {
    @Test
    void slotOffsetsAreAlignedAndDisjoint() {
        long align = 64; // 假设 5090 的 bufferDescriptorAlignment, 单测用假值即可验证算术
        assertEquals(0, HeapLayout.slotOffset(0, 0, align));          // cell 0 的 VBO 槽 = 堆头
        assertEquals(align, HeapLayout.slotOffset(0, 1, align));      // cell 0 的 IBO 槽 = 后一个对齐位
        long a = HeapLayout.slotOffset(1, 0, align);
        long b = HeapLayout.slotOffset(1, 1, align);
        assertTrue(b > a && (b % align) == 0);                        // 对齐且互不相交
        // 跨 cell: cell 1 的首槽恰在 cell 0 的整表之后(无重叠)
        assertEquals(HeapLayout.totalBytes(1, 2, align), a);
    }

    @Test
    void totalBytesCoversAllCellsAndSlots() {
        long total = HeapLayout.totalBytes(8192, 2, 64);
        assertEquals(8192 * 2 * 64, total); // 无预留区时的精确值(S1: reservedRange=0, 见笔记 §6)
    }
}
```

Run: `GRADLE_USER_HOME=$PWD/.gradle-user-home JAVA_HOME=$PWD/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew :mod:test --offline`（**先确认** `:mod:test` 在 fabric-loom 下可跑：若 loom 的 test 任务因 natives/mappings 报错，fallback = 把 `testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'` 与 `test { useJUnitPlatform() }` 加进 `mod/build.gradle` 后重试；仍不行则本单测改为"游戏内 init 时 log 断言值"的形态，测试期望值不变）。
Expected: 首跑 FAIL（`HeapLayout` 不存在）。

- [ ] **Step 2: 实现 HeapLayout 使测试通过**

`mod/src/main/java/dev/dhvk/heap/HeapLayout.java`：

```java
package dev.dhvk.heap;

/**
 * 描述符表槽位算术(纯 Java, 无 native): per-cell 子区按 S4 最坏预算预摊(R-c),
 * 堆表不重建不搬家。slot = 一个 buffer 描述符槽(VBO 或 IBO 各占一个)。
 */
public final class HeapLayout {

    private HeapLayout() {
    }

    /** cell 的第 slotIndex 个槽位在堆内的字节偏移(按 slotAlignment 对齐)。 */
    public static long slotOffset(long cell, int slotIndex, long slotAlignment) {
        return (cell * (long) 2 /* S1: 每 cell 2 槽 = VBO+IBO; S2 并入 UBO 时扩 */ + slotIndex)
                * slotAlignment;
    }

    /** 堆总大小 = cell 数 × 每 cell 槽数 × 槽对齐(S1 reservedRange=0, 笔记 §6 定稿)。 */
    public static long totalBytes(long cellCapacity, int slotsPerCell, long slotAlignment) {
        return cellCapacity * slotsPerCell * slotAlignment;
    }
}
```

Run: 同上 `:mod:test`。Expected: PASS。（若 fallback 到游戏内断言：`FarTerrainRenderer` init 时 log `HeapLayout.totalBytes(8192, 2, <实测alignment>)` 并与手算值比对。）

- [ ] **Step 3: DescriptorHeap（raw API 薄封装，形态以笔记 §3/§4/§6 为准）**

`mod/src/main/java/dev/dhvk/heap/DescriptorHeap.java` 骨架（调用形态按笔记改写）：

```java
package dev.dhvk.heap;

import dev.dhvk.VkHandles;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.VkDeviceAddressRangeEXT;
import org.lwjgl.vulkan.VkHostAddressRangeEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorHeapPropertiesEXT;
import org.lwjgl.vulkan.VkResult;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VmaPool; // 若 26.2 官方 GpuDevice 暴露 raw memory 分配则改用官方通道(笔记 §4)

/**
 * S1 descriptor heap 本体: 一段 GPU 内存 = 全体 cell 的描述符表(堆不是对象, 是内存范围)。
 * 持久堆(init 建一次) + 每帧绑定(vkCmdBindResourceHeapEXT 绑整表) + dirty 描述符重写。
 * 内存类型与写入路径按笔记 §3 实施决定(5090 优先 DEVICE_LOCAL+HOST_VISIBLE ReBAR 窗口)。
 */
public final class DescriptorHeap implements AutoCloseable {

    private final long sizeBytes;
    private final long slotAlignment;
    private long buffer;          // raw VkBuffer(承载堆内存, USAGE 按笔记 §3)
    private long memory;          // raw VkDeviceMemory
    private long baseDeviceAddr;  // vkGetBufferDeviceAddress
    private long baseHostAddr;    // host 映射窗口(若 §3 判定 host-visible 直写路径)
    private boolean closed;

    public DescriptorHeap(long cellCapacity, int slotsPerCell) {
        // 1) 查 VkPhysicalDeviceDescriptorHeapPropertiesEXT(resourceHeapAlignment/bufferDescriptorSize/
        //    bufferDescriptorAlignment/maxResourceHeapSize/maxPushDataSize)——vkGetPhysicalDeviceProperties2 pNext
        // 2) sizeBytes = HeapLayout.totalBytes(cellCapacity, slotsPerCell, bufferDescriptorAlignment)
        //    超 maxResourceHeapSize → 降 cellCapacity(运行时预算, spec §7 R4)并 log
        // 3) vkCreateBuffer(raw, §3/§4 决定的 usage/内存类型) + vkBindBufferMemory + vkGetBufferDeviceAddress
        // 4) 若 §3 判定 host-visible 直写路径: vkMapMemory → baseHostAddr; 否则走 vkWriteResourceDescriptorsEXT
        // 5) LOGGER.info("[dhvk] descriptor heap ready: {}B ({} cells), memType={}", ...)
    }

    public long slotOffsetOf(long cell, int slot) {
        return HeapLayout.slotOffset(cell, slot, slotAlignment);
    }

    /** 写一个 buffer 描述符(payload = arena 子区的设备地址区间)到 cell 的槽位。 */
    public void writeBufferDescriptor(long cell, int slot, long deviceAddress, long sizeBytes) {
        // 形态 A(笔记 §3 判定为 host 直写): 向 baseHostAddr+slotOffset 写 VkResourceDescriptorDataEXT 编码
        //   (buffer 描述符 = VkDeviceAddressRangeEXT{deviceAddress, rangeSize} 的 driver 编码, 编码格式按 spec)
        // 形态 B: vkWriteResourceDescriptorsEXT(VkHandles.device, 1, &resourceInfo, &hostRange)
        //   VkResourceDescriptorInfoEXT{type=VK_DESCRIPTOR_TYPE_BUFFER 或 §1 裁决的类型, data.pAddressRange=range}
        // 非 coherent 内存(§3 判据)→ 写后 vkFlushMappedMemoryRanges
    }

    /** 每帧一次: 绑整表范围(或 dirty 范围)。commandBuffer = 笔记 §2 钉死的发射点。 */
    public void bind(long commandBuffer, long offset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceAddressRangeEXT range = VkDeviceAddressRangeEXT.calloc(stack)
                    .deviceAddress(baseDeviceAddr + offset).rangeSize(size);
            // bindInfo 参数定稿 = 笔记 §6(reservedRange 语义实测)
            EXTDescriptorHeap.vkCmdBindResourceHeapEXT(commandBuffer,
                    VkBindHeapInfoEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_BIND_HEAP_INFO_EXT /* 1000135003 */)
                            .heapRange(range)
                            .reservedRangeOffset(0).reservedRangeSize(0)); // 0 的合法性 = 笔记 §6 结论
        }
    }

    public long deviceAddressAt(long offset) {
        return baseDeviceAddr + offset;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // vkDestroyBuffer + vkFreeMemory(顺序: buffer 先于 memory, VVL 对象追踪)
    }
}
```

- [ ] **Step 4: 双墙改走堆（S0 几何经堆源描述符出图——本任务的决定性证明）**

1. shader `far_terrain.vsh`：顶点输入声明改为笔记 §1(b) 的堆源语法（GLSL 原文 + `mod/scripts/compile-spirv.sh` 重编译，确认 spirv 产物更新）；
2. `FarTerrainRenderer`：`ensureBuffers()` 保留 S0 的 vbo/ibo `GpuBuffer`（作为"arena"雏形，任务 3 才换正式 arena），但其**设备地址**（笔记 §4 途径）用于 `heap.writeBufferDescriptor(0, 0/1, ...)` 写墙 A/B 的 VBO/IBO 描述符；`render()` 按笔记 §2 的发射点插入 `heap.bind(cbu, 0, 整表)`，`drawIndexed` 不变（firstVertex/firstIndex=0，子区寻址全在描述符里）；
3. pipeline：BGL 集合加几何 BGL（笔记 §1(a) 的声明代码），若走 raw pipeline（笔记 §5 判定）则按笔记的复刻骨架建；
4. `dispose()`：heap.close() 进有序释放（arena 之后、设备关闭之前）。

- [ ] **Step 5: 构建 + 标准档 runClient（先问用户）**

Run: 构建 + 标准档 runClient。
Expected: 日志有 `[dhvk] descriptor heap ready: ...`；双墙 A/B **仍正常出图**（用户目视：-400 处四色墙、-2000 处雾吞墙）；VVL 零新增报错（heap bind + 堆源描述符 + 堆源顶点输入全在 VVL 火力范围内）；干净退出（对象追踪零报错 → heap 的 buffer/memory 释放正确）。

- [ ] **Step 6: 全开档复验**

Run: 全开档 runClient（`VK_LAYER_SETTINGS_PATH` 保留）。Expected: 同样全绿。

- [ ] **Step 7: 提交**

```bash
git add mod/src/main/java mod/src/test/java mod/src/main/resources
git commit -m "S1 任务2: descriptor heap 核心(持久堆+槽位算术+buffer 描述符写入+每帧整表绑定) + 双墙几何改走堆源描述符(VVL 双档零报错)"
```

---

### Task 3: GeometryArena + 多 cell 静态绘制 + VRS 2×2

**Files:**
- Create: `mod/src/main/java/dev/dhvk/GeometryArena.java`
- Create: `mod/src/main/java/dev/dhvk/CellGeometryTable.java`
- Modify: `mod/src/main/java/dev/dhvk/FarTerrainRenderer.java`（双墙 → N 个静态合成 cell；VRS 状态接入）
- Modify: `mod/src/main/resources/assets/dhvk/shaders/core/far_terrain.vsh`（若 per-cell modelView 通道本任务才接——见 Step 1 判定）

**Interfaces:**
- Consumes: `DescriptorHeap`（任务 2）；笔记 §4（arena 地址）、§5（VRS 声明）
- Produces: `GeometryArena`（`long allocateSubRegion(int cellVerts, int cellIndices)` → 子区偏移；`void uploadSubRegion(long offset, ByteBuffer data)`；`long deviceAddressAt(long offset)`；`close()`）；`CellGeometryTable`（`void setCell(long cell, long vboOffset, int vboVerts, long iboOffset, int indexCount)` + dirty 描述符重写 → `heap.writeBufferDescriptor`）；per-cell 静态合成 cell 的 CPU 生成（`FarTerrainRenderer` 内 `putSyntheticCell(...)`，任务 5 移入 `SyntheticRing`）

- [ ] **Step 1: per-cell modelView 通道（若任务 2 的 shader 还是全局 DynamicTransforms）**

官方 chunk section 的 per-draw 机制 = `writeChunkSections`（`DynamicUniforms.java:61/65`，S0 计划 §2 已核实）+ shader 里的 `ChunkSection` uniform 块。`far_terrain.vsh` 加该块（#moj_import 与官方 `terrain.vsh` 同款），`render()` 每 cell 前 `setUniform("ChunkSection", slice)`（`slice` 来自 `RenderSystem.getDynamicUniforms().writeChunkSections(cellInfo)`，`modelView` = cell 原点平移矩阵，xyz = cell 世界原点）。若任务 2 已接好则本步跳过（在笔记里记一句）。

- [ ] **Step 2: GeometryArena（形态按笔记 §4：官方 GpuBuffer 够用则用，否则 raw）**

```java
package dev.dhvk;

/**
 * VBO/IBO 数据 arena(单块 buffer, per-cell 子区; R-c 子区容量按 S4 最坏预算预摊)。
 * 顶点 = 相对 cell 原点的坐标, 颜色 RGBA8(R-e)。per-dirty-cell 增量上传。
 */
public final class GeometryArena implements AutoCloseable {
    // 内部: 官方 GpuBuffer(USAGE_VERTEX|USAGE_INDEX) 或 raw buffer(笔记 §4)
    //   + 一个简单的子区分配器(偏移游标, 容量 = 驻留 8192 × 每 cell 最坏 64KB 预摊,
    //     或按 S1 高度场实际用量 ×1.5 的务实值——以笔记/实测为准, init 时 log 容量)
    public long allocate(long cell, int vertexBytes, int indexBytes); // 返回 vbo 子区偏移; ibo 偏移 = 同一子区的固定偏移
    public void upload(long vboOffset, ByteBuffer verts, long iboOffset, ByteBuffer indices);
    public long vboDeviceAddressAt(long vboOffset); // 笔记 §4 的地址途径
    public long iboDeviceAddressAt(long iboOffset);
    public synchronized void close(); // 进 FarTerrainRenderer.dispose() 有序链
}
```

- [ ] **Step 3: CellGeometryTable + 8 个静态合成 cell**

`CellGeometryTable`：`HashMap<Long, CellEntry>`（`CellEntry = {vboOffset, vboVertCount, iboOffset, indexCount, cellOriginX/Y/Z, baseColor}`），`setCell` 时若 dirty → `heap.writeBufferDescriptor(cell, 0/1, arena.vboDeviceAddressAt(...), size)`。
`FarTerrainRenderer`：把 S0 双墙替换为 **8 个 32×32 block 的合成 cell**（环绕出生点 512~1024 块距离，平面高度场 y=64+确定性伪随机起伏，每 cell 单色：洋红/青/黄/绿/品红蓝/橙/紫/白——用户目视可数、可辨位）；每 cell 的 VBO/IBO 上传进 arena、描述符写进堆；`render()` 每帧 `heap.bind` 后循环 8 cell：`setUniform("ChunkSection", cellInfoSlice)` + `drawIndexed(entry.indexCount, 1, 0, 0, 0)`（per-cell 偏移经 push constant——push constant 布局按笔记 §1 的 BGL 声明；若笔记判定 S1 可先用"每 cell 单独堆范围绑定"的更简形态，以笔记为准并记录）。

- [ ] **Step 4: VRS 2×2（按笔记 §5 实施决定）**

笔记 §5 形态 A（官方 pNext 可挂）：pipeline 的 VRS 状态 = 2×2 coverage modulation（结构体按笔记）。
形态 B（raw 自建 pipeline）：按笔记的复刻骨架（`VkPipelineRenderingCreateInfo` 抄官方 `createRenderPass` 的格式/采样/view mask），pipeline 走 raw，render pass 仍走官方 `RenderSystem.getDevice().createCommandEncoder().createRenderPass(...)`（S0 模板）。
VRS 生效证据按笔记 §5 定的方法留档（VVL 日志 / 采样率 dump 二选一，S1 内定一种并写进笔记）。

- [ ] **Step 5: 构建 + 标准档 runClient（先问用户）**

Run: 构建 + 标准档 runClient。
Expected（用户目视 + 日志）：8 个合成 cell 出现在各自世界坐标处（-X 方向地平线附近，RD32 下在 [448, 2048] 带内可见段），颜色各异可数；被近景地形正确遮挡（reverse-Z 深度融合）；VVL 零新增报错；干净退出（arena/table 进 dispose 链，对象追踪零报错）。

- [ ] **Step 6: 全开档复验 + 提交**

Run: 全开档 runClient（Expected 同上）。

```bash
git add mod/src/main/java mod/src/main/resources
git commit -m "S1 任务3: GeometryArena(per-cell 子区+R-c 预摊) + CellGeometryTable + 8 静态合成 cell 出图 + VRS 2×2 pass 级(VVL 双档零报错)"
```

---

### Task 4: CellRegistry + HeightfieldExtractor + BlockTileUploader（R-b）+ 单测

**Files:**
- Create: `mod/src/main/java/dev/dhvk/CellRegistry.java`
- Create: `mod/src/main/java/dev/dhvk/HeightfieldExtractor.java`
- Create: `mod/src/main/java/dev/dhvk/BlockTileUploader.java`
- Create: `mod/src/test/java/dev/dhvk/CellRegistryTest.java`
- Modify: `mod/build.gradle`（JUnit + `useJUnitPlatform`，任务 2 fallback 触发过则已有）
- Modify: `mod/src/main/java/dev/dhvk/FarTerrainRenderer.java`（任务 3 的静态 8 cell → 注册表驱动的动态 cell；真实 chunk 数据进 `CellGeometryTable`）

**Interfaces:**
- Consumes: `DescriptorHeap`/`GeometryArena`/`CellGeometryTable`（任务 2/3）；`VkHandles`（raw SSBO 用）
- Produces: `CellRegistry`（`void frameUpdate(Camera, Level)` → `List<Long> visibleCells`；`void markDirty(long cell)`；内部：帧建 ≤8 / 驱逐 ≤2 / 驻留 8192 LRU / 距离带 [fogStart, depthFar] + 视锥 + Y 带剔除）；`HeightfieldExtractor`（`Extraction extract(Level, long cell)` → `{float[] verts, int[] indices, byte[] colors, byte[] blockTile32x32x32}`，纯逻辑部分可单测）；`BlockTileUploader`（`void upload(long cell, byte[] tile32x32x32)` → raw SSBO arena 子区；`close()`）

- [ ] **Step 1: CellRegistry 失败测试（TDD）**

`mod/src/test/java/dev/dhvk/CellRegistryTest.java`：

```java
package dev.dhvk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CellRegistryTest {

    @Test
    void buildBudgetCapsAtEightPerFrame() {
        CellRegistry reg = new CellRegistry(8192, 8, 2);
        for (int i = 0; i < 100; i++) reg.markDirty(CellRegistry.cellKey(i / 10, i % 10));
        int built = reg.frameBuild(0); // 本帧构建数
        assertEquals(8, built);
        built = reg.frameBuild(0);
        assertEquals(8, built);
        // 剩余 84 个脏 cell 排队, 逐帧消化
        assertTrue(reg.pendingDirty() > 0);
    }

    @Test
    void evictionIsLruAndCappedAtTwoPerFrame() {
        CellRegistry reg = new CellRegistry(16, 8, 2); // 小容量便于测试
        for (long i = 0; i < 16; i++) { reg.forceBuild(CellRegistry.cellKey(0, (int) i)); }
        assertEquals(16, reg.residentCount());
        reg.touch(CellRegistry.cellKey(0, 0)); // 只访问 0 号, 其余变冷
        int evicted = reg.frameEvict();
        assertEquals(2, evicted); // 帧驱逐上限 2
        assertNotEquals(0, reg.mostRecentlyEvicted() & 0xFF); // 0 号不应最先被逐(LRU)
    }

    @Test
    void residentCapIsHardLimit() {
        CellRegistry reg = new CellRegistry(4, 8, 2);
        for (long i = 0; i < 10; i++) reg.forceBuild(CellRegistry.cellKey(0, (int) i));
        assertEquals(4, reg.residentCount()); // 驻留上限硬限
    }
}
```

Run: `:mod:test --offline`。Expected: FAIL（`CellRegistry` 不存在）。

- [ ] **Step 2: 实现 CellRegistry 使测试通过**

`CellRegistry`（纯逻辑，无 native 依赖——剔除几何部分用 joml `FrustumIntersection`（游戏随包）+ 世界 Y 带，与 DH `DhFrustumBounds` 同款姿势）：

```java
package dev.dhvk;

/**
 * cell 注册表 = 几何唯一权威(R-a)。渲染器只消费 "cell -> (几何子区, UBO)"。
 * 帧建 <= buildBudget / 帧驱逐 <= evictBudget / 驻留 <= residentCap(LRU)。
 * 距离带 = [官方雾起点, 官方 depthFar](RD32 = [448, 2048]); 剔除 = 距离带 + 视锥 + 世界 Y 带。
 */
public final class CellRegistry {

    public static long cellKey(int ix, int iz) {
        return ((long) (ix & 0x3FFFFFFF) << 32) | (iz & 0x3FFFFFFFL);
    }

    private final int residentCap;
    private final int buildBudget;
    private final int evictBudget;
    // 内部: HashMap<Long, CellSlot> resident; LinkedHashMap<Long,Long> lru;
    //       Set<Long> dirty; (构建/驱逐预算队列)

    public CellRegistry(int residentCap, int buildBudget, int evictBudget) { ... }

    public void markDirty(long cell) { ... }          // chunk 加载事件 → dirty(S1 粗策略: 加载即重建)
    public int frameBuild(int frameIndex) { ... }     // 本帧构建, 返回构建数(<= buildBudget)
    public int frameEvict() { ... }                   // LRU 驱逐, 返回驱逐数(<= evictBudget)
    public int residentCount() { ... }
    public long mostRecentlyEvicted() { ... }
    public int pendingDirty() { ... }
    public void touch(long cell) { ... }              // 更新 LRU
    public void forceBuild(long cell) { ... }         // 测试/合成环用: 无视预算直接驻留
    /** 帧剔除: 距离带 + 视锥 + Y 带 → visible cell 列表(目标 <= 2000, 超限按距离截断并 log)。 */
    public java.util.List<Long> frameUpdate(float camX, float camZ, float camY,
            float bandNear, float bandFar, org.joml.Frustum frustum) { ... }
}
```

Run: `:mod:test --offline`。Expected: PASS。

- [ ] **Step 3: HeightfieldExtractor（DAG"提取"阶段的 CPU 执行器）**

```java
package dev.dhvk;

/**
 * DAG 阶段"表面提取"(R-d: 同一张图, S4 换 GPU compute 执行器不换图):
 * 每列自顶向下首个非 air block(S1 简化: 不处理悬空/崖面, S2 加子采样+崖面规则)。
 * 输出: verts(cell 原点相对, R-e: pos+RGBA8 无光) / indices / baseColor / blockTile(32x32x32 量化 ID, R-b)。
 * 数据源 = 内存中已加载的 chunk sections(R5: 加载过才有); 未加载列 → 该列顶面 = 海平面基线 64。
 */
public final class HeightfieldExtractor {
    public static final int CELL_SIZE = 32;

    /** 纯逻辑核心(可单测: 传一个 int[32][32][32] 的块 ID 体)——Level 查询只是它的喂数器。 */
    public static Extraction extractFromBlocks(byte[] blocks /* 32x32x32 行主序, 0=air */, int[] paletteBaseColor);
    /** 游戏内喂数: 从 Level 的已加载 chunk 查 32x32x32 块 ID 体, 再调 extractFromBlocks。 */
    public static Extraction extract(net.minecraft.world.level.Level level, long cell);
}
```

`Extraction` record：`record Extraction(float[] verts, int[] indices, byte[] colors /* RGBA8 per-vertex */, byte[] blockTile /* 32x32x32 */, float baseColorRgb /* 3 分量 */) {}`。几何生成：每列顶面 block 的顶面顶点（相邻列高差 → 三角化，S1 最简：每列 1 顶点 + 每 2×2 列 2 三角形，与 S0 计划 §1 的"33×33 顶点网格"同族，S1 用每列 1 顶点的简化网格以省几何量——三角化规则写进代码注释，S2 再升级）。

- [ ] **Step 4: BlockTileUploader（R-b：S1 起同步上传）**

```java
package dev.dhvk;

import dev.dhvk.VkHandles;

/**
 * R-b: 每 cell 的 32x32x32 block-ID 量化 tile(~3-8KB) → raw VkBuffer SSBO arena。
 * 官方 GpuBuffer 无 STORAGE usage → 走捕获句柄的 raw 通道(阶段一 R3 既定路线)。
 * S1/S2 的 CPU 提取不消费它——S4 GPU mesher 的输入, 禁止"先不传、S4 再补通道"。
 */
public final class BlockTileUploader implements AutoCloseable {
    // raw: vkCreateBuffer(STORAGE|TRANSFER_DST) + vkBindBufferMemory(笔记 §4 的内存类型判据)
    //      单块 arena, per-cell 子区(32*32*32 字节), 增量 vkCmdUpdateBuffer / staged upload
    public void upload(long cell, byte[] tile); // 子区偏移 = cell * 32768(按实测对齐微调)
    public long tileDeviceAddressAt(long cell); // 给 S4 的 compute shader 用(本切片只上传+log)
    public synchronized void close(); // 进 dispose 有序链
}
```

init 时 log `[dhvk] block-tile arena ready: 8192 cells x 32KB`（驻留容量对齐）。

- [ ] **Step 5: FarTerrainRenderer 接注册表（静态 8 cell → 动态）**

`frameUpdate`：相机参数（`RenderSystem.getGameRenderer().getCamera()`）+ 距离带 [雾起点, depthFar]（RD32 = [448, 2048]，值从 `Fog` uniform/相机读）→ `registry.frameUpdate(...)` → visible 列表 → 逐个：若未驻留 → 本帧预算内 `HeightfieldExtractor.extract` → arena 上传 + 描述符写堆（DAG：[block ready]→[提取]→[上传]→[表更新]）+ `BlockTileUploader.upload`；绘制循环不变（`setUniform("ChunkSection", ...)` + `drawIndexed`）。脏 cell（chunk 加载事件 → `markDirty`；S1 事件源 = 每帧 diff 已加载 chunk 集合的粗策略）走重建预算。

- [ ] **Step 6: 构建 + 标准档 runClient（先问用户）**

Run: 构建 + 标准档 runClient。
Expected（用户目视 + 日志）：出生在默认世界，远处（加载半径外缘附近）开始出现**真实地形**的无光剪影 cell（数据薄环——A+C 口径的观察项：有数据才有，边缘在官方雾墙外缘溶进雾色）；走动时 cell 按预算构建/驱逐（日志 `[dhvk] streaming: built=.. evicted=.. resident=.. pending=..` 每 600 帧一行）；block-tile arena 上传日志在 init 出现；VVL 零新增报错；干净退出（uploader/registry 无 native 泄漏——registry 纯 Java，uploader 的 raw buffer 进 dispose 链）。

- [ ] **Step 7: 单测全量 + 提交**

Run: `:mod:test --offline`（全部 PASS）。

```bash
git add mod/src/main/java mod/src/test/java mod/build.gradle
git commit -m "S1 任务4: CellRegistry(预算/LRU/驻留硬限+帧剔除) + HeightfieldExtractor(DAG CPU 提取器) + BlockTileUploader(R-b tile 同步上传) + 单测(真实地形薄环出图, VVL 零报错)"
```

---

### Task 5: 合成调试环 + 淡入 + 计时 + 全带流式（机制验收主战场）

**Files:**
- Create: `mod/src/main/java/dev/dhvk/SyntheticRing.java`
- Modify: `mod/src/main/java/dev/dhvk/FarTerrainRenderer.java`（F3+K 开关轮询；pass 计时 log；淡入经 `writeChunkSections` 的 visibility 分量）
- Modify: `mod/src/main/java/dev/dhvk/CellRegistry.java`（淡入状态字段，若任务 4 未留）

**Interfaces:**
- Consumes: 任务 2/3/4 全部产物
- Produces: `SyntheticRing`（`void populate(CellRegistry, GeometryArena, CellGeometryTable, DescriptorHeap, BlockTileUploader)`：[fogStart, depthFar] 全带合成高度场 cell 写入注册表——确定性：高度 = 64 + 8·sin(ix·0.7)·cos(iz·0.5) + cell 坐标哈希伪随机 ±4（同 cell 每次生成同数据，可复现）；F3+K 开关（开 = 合成环接管剔除，关 = 真实 chunk 数据））；pass CPU 计时（每 600 帧 log `far-pass cpu=..ms`）

- [ ] **Step 1: SyntheticRing（确定性全带合成数据源）**

```java
package dev.dhvk;

/**
 * 合成调试环: 机制验收的全带靶子(A+C 口径: 不计视觉验收)。
 * [fogStart, depthFar] 全带铺确定性合成高度场 cell —— 让流式预算/VRS/heap
 * 在"满负荷全带"下被验收, 不受 RD32 加载半径(512 块)的数据上限制约。
 * F3+K 运行时开关(帧循环轮询 GLFW.glfwGetKey, 窗口句柄 = 任务 0 笔记的探针捕获)。
 */
public final class SyntheticRing {
    public static boolean enabled = false;

    /** 开关键处理放在 FarTerrainRenderer.frame 入口: F3 按住 + K 按下沿 → 翻转 enabled, log 一行。 */
    public static void toggleIfPressed(long glfwWindow) { /* GLFW.glfwGetKey(window, F3) & K */ }

    /** 把全带合成 cell 灌进注册表(绕过真实 chunk 数据; 几何 = 同公式高度场, 走完整 DAG 上传链)。 */
    public static void populate(...) { ... }
}
```

几何生成复用 `HeightfieldExtractor.extractFromBlocks`（喂合成块 ID 体）——合成环与真实数据**同一条提取/上传/表更新链**（R-a：渲染器对来源无感知）。

- [ ] **Step 2: 淡入（pop-in 消除）**

per-cell visibility 淡入经 S1 的 UBO 通道（`writeChunkSections` 的 `visibility` 分量，`ChunkSectionInfo` 第四参）：新 cell 从 0 淡入到 1，时长 = 官方 chunk fade（读 `LevelRenderer`/官方 chunk fade 的实现值——任务 4 执行时顺带核实并写进笔记；缺省 100ms，以实测为准）。驱逐侧**不**淡出（驱逐即出带，被雾/距离带走，S1 够用；记录在案）。

- [ ] **Step 3: 计时 + 全带验收跑**

`render()` 入口/出口 `System.nanoTime()` 计 pass 录制 CPU 时间，每 600 帧 log `[dhvk] far-pass cpu=..ms cells_visible=.. cells_resident=..`。
**验收跑（先问用户）**：标准档 runClient → 进世界 RD32 → F3+K 开合成环 → 走动/转头 3 分钟（用户目视：全带剪影无 pop-in 爆闪、无抖动）→ 退出。
Expected: 日志流式计数在全带满负荷下预算不爆（built ≤ 8/帧、resident ≤ 8192）；`far-pass cpu` 数值稳定（5090 上 +1ms/帧以内——与进游戏前 F3 的 ms 值对比，用户读数）；VVL 零新增报错；干净退出。

- [ ] **Step 4: 全开档复验（sync + gpu_assisted + best_practices 全开）**

Run: 全开档 runClient（同样 F3+K 流程）。Expected: 全绿（GPU 辅助同步校验对 heap bind/描述符读访问 `VK_ACCESS_2_RESOURCE_HEAP_READ_BIT_EXT` 的火力覆盖）。

- [ ] **Step 5: 提交**

```bash
git add mod/src/main/java
git commit -m "S1 任务5: 合成调试环(全带确定性数据源+F3+K) + 官方 chunk fade 同款淡入 + pass 计时; 全带流式机制标准/全开双档零报错"
```

---

### Task 6: 验收归档（A+C 机制门全项销账）

**Files:**
- Create: `docs/s1-acceptance/`（验收记录 + 各轮日志 + 截图）
- Modify: `docs/superpowers/plans/2026-09-15-phase2-dh-geometry-hauler.md`（§4 S1 节回填验收结果，S0 同款）

**Interfaces:**
- Consumes: 任务 1~5 全部提交 + 各轮 run 日志
- Produces: S1 验收记录（spec §1 七项逐条销账）；阶段二计划文档 S1 回填

- [ ] **Step 1: 负门槛 build 终验**

Run: `DHVK_NOSURGERY=1` + 标准档 runClient（先问用户）。
Expected: `[dhvk] device gate FAILED ... mod disabled, vanilla renderer running`；无手术日志；双墙/合成环均不出现；VVL 零报错；退出 0。（= spec §1 验收 #2 的正式证据）

- [ ] **Step 2: 标准档 + 全开档终验跑（各一轮，先问用户）**

Run: 标准档 runClient（RD32，真实数据 + 合成环各体验一遍，3 分钟）→ 全开档 runClient（同）。
Expected: 双档 VVL 零 validation / 零 object-tracking；与 `docs/baselines/` vanilla 基线 diff 干净（仅多：我们的 pass 录制、设备扩展一项、heap 相关 VVL 无消息）；退出 0。

- [ ] **Step 3: 截图 + diff 留档**

游戏内 F2 截图（合成环全景一张 + 真实薄环一张 + F3 性能读数一张）→ 存 `docs/s1-acceptance/`；基线 diff 输出存 `docs/s1-acceptance/baseline-diff-<标签>.txt`。

- [ ] **Step 4: 写验收记录 `docs/s1-acceptance/2026-09-14-s1-accepted.md`（全绿才用此名；有未销账项则名 `...-s1-partial.md` 并列出）**

内容模板（S0 同款）：
1. spec §1 验收表七项逐条 PASS/观察项状态（#1 硬门槛正路径——任务 1 日志；#2 负路径——本任务 Step 1；#3 heap 载体双零——任务 2/5 日志；#4 VRS——任务 3 的生效证据；#5 合成环全带 + 预算 + 1ms + 无 pop-in——任务 5；#6 基线 diff——本任务 Step 2/3；#7 真实薄环——观察项，任务 4/5 截图）；
2. 本片中招清单（≤5 行/条，S0 文化）；
3. 日志/截图索引。

- [ ] **Step 5: 回填阶段二计划 + 提交**

`docs/superpowers/plans/2026-09-15-phase2-dh-geometry-hauler.md` 的 §4 S1 节验收块回填结果（S0 当时同款回填）；S2 前置依赖核对（本切片给 S2 留的接口：LOD 上卷 = 注册表的 parent cell 机制位、UBO 并入堆 = `CellGeometryTable` 的槽位扩位）。

```bash
git add docs/s1-acceptance docs/superpowers/plans
git commit -m "S1 验收通过: 标准档+全开档 VVL 零报错, 硬门槛正/负双路径实证, 合成环全带流式+1ms 达标, 证据归档 docs/s1-acceptance/"
```

---

## Self-Review（计划作者自查，已跑）

1. **Spec 覆盖**：spec §1 验收 7 项 → 任务 1(#1 #2)/任务 2+5(#3 #5)/任务 3(#4)/任务 2+6(#6)/任务 4+6(#7)；§2 设备手术+门槛+VRS → 任务 1/3；§3 heap 本体（持久堆/每帧绑定/HEAP_WITH_CONSTANT_OFFSET/arena/block tile/DAG）→ 任务 2/3/4；§3.4 读码先决 6 项 → 任务 0 逐节；§4 帧循环（注册表/剔除/预算/淡入/合成环）→ 任务 4/5；§5 帧图接线与关断 → 任务 2/3/4 的 dispose 扩展 + 任务 6 的干净退出验证；§6 执行协议 → Global Constraints + 各任务 run 步骤；§7 风险退路 R1~R6 → 分别落在任务 3(VRS raw 退路)/任务 2(shader 语法)/任务 2(写入路径)/任务 2(堆容量降级)/任务 0(CBU 探针)/任务 0(探针重布)。无缺口。
2. **Placeholder 扫描**：所有"以笔记 §N 为准"的步骤都绑定了任务 0 的具体产出节（笔记在任务 0 落地后才开任务 2+，顺序保证笔记存在）；无 TBD/TODO；`SyntheticRing.populate(...)` 的 `...` 参数 = 任务 4 已定义的五个组件类型（Interfaces 块列明）。
3. **类型一致性**：`DescriptorHeap.slotOffsetOf(cell, slot)`（任务 2 定义）与任务 3 `CellGeometryTable` 的调用一致；`CellRegistry.cellKey/frameUpdate/markDirty/forceBuild/residentCount`（任务 4 定义）与任务 5 `SyntheticRing.populate` 的消费一致；`GeometryArena.allocate/upload/vboDeviceAddressAt`（任务 3 定义）与任务 4 提取链一致；`VkHandles.capture` 四参签名（任务 0）全计划统一。

## 执行备注（给执行者）

- **顺序铁律**：任务 0 的笔记没写完不开任务 2+（任务 1 只依赖任务 0 的探针，可与笔记 §2 的读码并行开工）。
- **每轮 runClient 前先问用户**（用户盯屏）；用户说"没问题/好" = 该轮目视验收通过。
- **VVL 报错不要自己猜**：逐条读消息 → 回笔记对应节 → 拿不准的写进当天 `/tmp/s1-notes.md`（会话 tmp）再修。
- **mixin 钩点失败（InvalidInjectionException）= 钩点对错了**，回 `mc-src/client/` 源码核 `@At` 目标，别硬试。
- 提交信息中文、带任务号（各任务 Step 已给出模板）。
