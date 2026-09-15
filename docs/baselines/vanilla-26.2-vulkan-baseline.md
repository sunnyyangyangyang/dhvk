# 原版 MC 26.2 Vulkan 基线（S0 验收 diff 参照）

## 首次运行（2026-09-14 20:11，用户宿主终端）
- 宿主：Fedora 44 + RTX 5090 + wayland；`vulkan-validation-layers 1.4.341.0-2.fc44`（dnf）
- 启动：`run-vanilla-vk.sh`（CLI 实参：`--vulkanValidation --graphicsBackend vulkan
  --gameDir .vanilla-run --accessToken offline --version 26.2`）
- 结果：**跑通**（游戏完整启动并正常退出，`options.txt` 已落盘）

## 已核实的运行目录事实（2026-09-15 栞从 `.vanilla-run/` 读取）
- `options.txt`（游戏完整 boot 的标志）：
  - `preferredGraphicsBackend:"default"`——游戏内保存的是 auto；**本次运行由 CLI
    强制 vulkan**。今后一切基线/mod 运行都必须走本脚本（显式钉 vulkan），
    不能依赖 auto 选择；
  - `graphicsPreset:"fancy"`，`renderDistance:16`，`cloudRange:64`
    → 官方 far 平面 = `max(16·16·4, 64·16)` = **1024 blocks**；
  - vsync on，maxFps 120。
  - 阶段二 mod 运行时建议把 renderDistance 拉到 32+（far=2048+），
    否则 hauler 的 512+ 距离带大半落在官方渲染范围之外，剪影对比不明显。
- `downloads/log.json` = 资产下载器运行记录（资产全内嵌 jar，下载量极小，符合预期）。
- **26.2 没有文件日志**：client.jar 内无 log4j2.xml，`logs/` 下只有 telemetry JSON
  → 基线/模运行的一手证据 = **控制台输出**。脚本已加 `--log <file>`
  （tee 控制台），今后基线运行一律带：
  `bash mc-src/scripts/run-vanilla-vk.sh --log mc-src/.vanilla-run/logs/console-baseline.txt`

## 控制台证据（`baseline.log`，2026-09-14 20:40 运行，2026-09-15 用户回填，栞逐行核对）
- 行2：`validation layer: /usr/share/vulkan/explicit_layer.d/VkLayer_khronos_validation.json`（脚本探测 ✓）
- 行6：`Backend library: LWJGL version 3.4.1+2`（与 artifacts 内 lwjgl-vulkan-3.4.1.jar 一致 ✓）
- 行7：`Graphics backend forced to vulkan by launch argument, in-game preferred graphics backend setting is ignored`（CLI 钉死生效 ✓）
- 行8：`Vulkan validation layers requested but not found` ⚠️（根因与修复见下节）
- 行25：`Using graphics backend Vulkan, using drivers: 1.4.351 NVIDIA 615.71.09`
- 行26：`Using graphics device: NVIDIA GeForce RTX 5090 (NVIDIA)`（dGPU ✓，F3 亦标 `dGPU`）
- 行27：设备**启用**扩展 = VK_KHR_synchronization2 / VK_EXT_multi_draw / VK_AMD_buffer_marker /
  VK_EXT_vertex_attribute_divisor / VK_KHR_swapchain / VK_KHR_surface / VK_KHR_push_descriptor /
  VK_KHR_xcb_surface（XWayland）/ VK_EXT_debug_utils / VK_KHR_dynamic_rendering。
  原版未启用 `VK_EXT_descriptor_heap` 与 VRS（符合预期）→ **S0 验证项**：
  `vkEnumerateDeviceExtensionProperties` 确认二者在本设备可用。
- 无害错误：`Failed to fetch user properties 401`（离线 token 的预期行为）；
  `Couldn't set icon`（icon 相对路径，表面问题）。
- 控制台无 queue/swapchain 明细行（INFO 级不打印）→ 帧提交行为 diff 以
  未来 `VK_LOADER_DEBUG=driver` 或 VVL 报告为准。

## 校验层 "requested but not found" 根因（2026-09-15 诊断，已修复）
排查链：① `VK_LOADER_DEBUG=layer` 实测（宿主 loader 沙箱复现）证明**清单被
loader 发现**（`/usr/share/vulkan/explicit_layer.d/VkLayer_khronos_validation.json`
在搜索列表内）；② `ldd` 证明层 `.so` 依赖全部齐全；③ 清单
`name = VK_LAYER_KHRONOS_validation` 与游戏请求名一致。
**根因**：26.2 `VulkanInstance.java:43` 把实例 apiVersion 钉在 **VK 1.2**
（`.apiVersion(VK12.VK_API_VERSION_1_2)`），而 Fedora VVL 1.4.341 清单声明
`api_version: 1.4.341` —— Vulkan loader 会把"声明比应用 API 更新"的 layer
从应用可见列表滤掉 → 游戏枚举不到 → WARN。层二进制本身完全没坏。
**修复**（已进 `run-vanilla-vk.sh`）：脚本自动生成私有清单
`$RUN_HOME/vulkan-layers/VK_LAYER_KHRONOS_validation.json`（系统清单仅把
api_version 降为 1.2.0 的副本，层二进制仍用系统 VVL），并前置进
`VK_LAYER_PATH`（loader 按搜索序取同名 layer 的首个清单）。
**已验证（2026-09-15 20:56 重跑，`console-baseline.txt`）**：
- 行15：`Enabling Vulkan validation layers` ✓（不再是 not found，补丁生效）
- 全程 **VVL 消息数 = 0**（无 VUID 报错/告警）→ **原版 26.2 Vulkan 渲染器
  在 VVL 下零 error 的基线成立**，S0 的"VVL 零 error"验收从此有了对照物；
- 设备行与首次运行一致（RTX 5090、驱动 1.4.351、同十项扩展），干净退出。

## 视觉基线（`.vanilla-run/screenshots/`，2026-09-14 20:41，官方 26.2 Vulkan 渲染器实拍）
- `2026-09-14_20.41.11.png`：雪原 + 冰海 + 远山剪影 + 方块云；地平线雾行为
  正常（S3 边界雾打磨参照）。
- `2026-09-14_20.41.19.png`（F3）：`75 fps T: 120 (fifo)`、fancy-clouds B:2、
  Filtering RGSS、Mem 44% 6826/15402MiB（alloc 877MiB/s）、
  `Minecraft 26.2 (26.2/vanilla)`、Java 25.0.4.1、CPU 32x AMD Ryzen 9 9950X、
  Display 3400x1363 (NVIDIA)、`NVIDIA GeForce RTX 5090 (dGPU)`、
  `Vulkan 1.4.351 NVIDIA 615.71.09`、集成服务端 2151 rx。

## 用途
- S0 验收：mod 注入前后与本文档 diff（帧提交方式 = fenceless Submit2、
  队列使用、pipeline/pass 计数、GPU 型号）；
- S3 验收：远平面扩展/边界雾打磨的"官方近景不受影响"参照。
