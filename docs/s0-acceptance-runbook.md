# S0 验收运行手册(宿主终端)

> 目标:在官方 26.2 Vulkan 渲染器上,用自有 `far_terrain` pass 在 **4000 块外**
> 画出一面 16×16 四色墙(顶点色、无光照、走官方 apply_fog),
> 与 vanilla 的 **VVL 零错误基线** 做差分。
> 代码:`mod/` 内 `FarTerrainRenderer` + `LevelRendererFarPassMixin`
> (注入点 = `LevelRenderer.render` 内 addCloudsPass 之后、addWeatherPass 之前的 INVOKE)。

## 为什么是这些参数

> **26.2 实测发现(宿主首跑 21:18 崩溃日志 + Options.java:1474)**:
> RD 滑块区间 **[2,32]**(maxMemory≥1GB 时上限 32),options.txt 写超界值会被拒
> (`Value 256 outside of range [2:33]`)。RD32 下:雾距 end=512/start=448,
> 投影远平面 depthFar = max(32·16·4, cloudRange·16) = **2048 块**。
> 官方相机物理上看不到 4000 块 → **4000 块规格由 S3(自有远平面+边界雾)承接**,
> S0 改用双墙: A = -400 块(雾带之前,0% 雾,四色全显), B = -2000 块
> (远平面之内、雾距之外,100% 官方雾)。

| 项 | 值 | 理由 |
|---|---|---|
| renderDistance | **32**(滑块上限) | 雾距 end=512/start=448、depthFar=2048 —— 双墙 A/B 的几何依据 |
| renderClouds | **false** | 云在 y≈512 的层会挡住地平线方向的远墙;关闭后验收图干净(S1+ 会重新共存) |
| graphicsPreset | fancy | 与 VVL 基线同档,差分可比 |
| --graphicsBackend vulkan | CLI | options 里 `preferredGraphicsBackend:"default"`,CLI 强制 Vulkan(与基线脚本同法) |
| --vulkanValidation | CLI | 与基线同开 VVL,要求全程零报错(层 = 脚本补丁过的 api_version 1.2 manifest) |

预置文件:`mod/run/options.txt`(由 vanilla 基线 options.txt 派生,仅改上述三项)。
runClient 的 gameDir 即 `mod/run`,游戏启动后读它、退出时覆写它。

## 运行(宿主终端)

**一键(推荐)**:

```bash
cd /home/<user>/Documents/dh-vk2026
bash mc-src/scripts/run-mod-vk.sh            # 默认 VVL 基础档,日志 s0-run-<HHMMSS>.log
bash mc-src/scripts/run-mod-vk.sh --full     # VVL 全开全包档,日志 s0-run-full-<HHMMSS>.log
```

脚本自动完成:serve-mappings 服务(8726,已活则跳过)、JAVA_HOME/GRADLE_USER_HOME
(工作区内 Temurin 25 + loom 缓存)、VVL 补丁 manifest(VK_LAYER_PATH 指向
`mc-src/.vanilla-run/vulkan-layers`,与 vanilla 基线同源)、`--full` 时追加
`VK_LAYER_SETTINGS_PATH=mc-src/scripts/vvl-fullprofile`。

**手动展开(等价四步,排障时用)**:

```bash
bash mc-src/scripts/serve-mappings.sh &
export JAVA_HOME=$PWD/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2
export GRADLE_USER_HOME=$PWD/.gradle-user-home
export VK_LAYER_PATH=$PWD/mc-src/.vanilla-run/vulkan-layers
./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation" 2>&1 | tee s0-run-1.log
```

各段命令的出处见文末「命令血统」一节。

进游戏:随便一个世界(与基线同世界更好),**朝 -X(西)方向看地平线**
(墙 A 400 块、墙 B 2000 块,同一方位)。

## 验收清单(逐条回报)

1. **出图(墙 A,-400 块)**:地平线处出现 16×16 四色墙(洋红/青/黄/绿各占一角,
   0% 雾、颜色全显,约 1px 级窄条)。
2. **遮挡(墙 A)**:走到山丘/悬崖后面朝 -X 看,墙被近处地形挡住(共享 reverse-Z 深度);
   绕回空处又出现。
3. **远带(墙 B,-2000 块)**:官方远平面(2048)之内、雾距(512)之外 —— 几何仍被绘制,
   颜色 100% 官方雾;若雾色 alpha<1,地平线雾墙处应可见极淡的彩色晕,
   完全看不见也不算挂(该项验证的是"官方相机内的超远几何走同一 pass 出图")。
4. **雾管线**:墙 A 位于雾带之前(448 之前)→ 应无雾;若 A 也泛雾色,回报实际观感。
4. **VVL**:控制台首屏有 `Enabling Vulkan validation layers`;全程无
   `Validation` 层消息(对照基线 = 0 条)。
5. **回归**:近处世界渲染与基线截图一致(地形/云关/光照正常),F3 数据合理,
   退出无崩溃。
6. **日志**:`s0-run-1.log` + 一张墙可见时的截图 + 一张 F3 截图回传。

## 加严档:全开全包 VVL(可选,建议 S1 前至少跑一次)

默认 VVL 只跑基础检查。三个隐藏重武器经 `vk_layer_settings.txt` 的 `enables` 打开
(配置已备好:`mc-src/scripts/vvl-fullprofile/`):

| 开关 | 抓什么 | 代价 |
|---|---|---|
| SYNCHRONIZATION_VALIDATION | 缺 barrier / 线程竞争 / 错误同步(最常见的 GPU 崩溃源) | 中等 CPU 开销 |
| GPU_ASSISTED | shader 插桩:buffer/image 越界、描述符不匹配、未初始化读 | 高(着色器重编译 + 逐 draw 检查) |
| API_BEST_PRACTICES | 语法正确但会坑驱动的危险用法 | 低 |

```bash
# 在上面的 4 步之后,追加一个环境变量即可:
export VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile
# 重新 runClient。三个开关里 GPU_ASSISTED 最贵:RD256 下帧率可能掉到个位数,属正常。
```

说明:
- 一票否决(自定义 VkDebugUtilsMessenger 收到 ERROR 即 abort)在 S1 用探针抓到的
  裸 VkDevice + LWJGL 3.4.1 的 KHRDebugUtils 绑定在 mod 内实现;S0 先靠官方
  `VulkanDebug` 的 messenger + 控制台人工判读。
- 贴标签:官方后端已给 buffer/纹理/pass 传了 label(本 mod 的 buffer 与
  `FarTerrain` pass 同样带名),VVL 报错时会直接印出来,无需额外工作。
- Valgrind/ASan 级 CPU 内存防护:等 S1/S4 开始写裸句柄计算代码时再上
  (Java 进程上 ASan 需要特制 JVM,Valgrind 可直接套,代价是慢)。

## 命令血统(各片段来源,均已源码/实测验证)

| 片段 | 出处 |
|---|---|
| `:mod:runClient` | fabric-loom(1.17.20,`mod/build.gradle`)自动生成的 dev-run 任务:dev classpath = loom 缓存的 26.2 反混淆 jar + fabric-loader 0.19.5 + mod class;gameDir = `mod/run`(预置 options.txt 生效于此);用法首见于阶段一 `docs/mc26.2-vk-reference.md:88-95` |
| `--args="..."` | Gradle JavaExec 参数透传;26.2 `Main.java` 的 joptsimple 带 `allowsUnrecognizedOptions()`,loom 自行补上必填的 `--accessToken/--version`,透传旗标原样进 `OptionParser` |
| `--graphicsBackend vulkan` | 26.2 `Main.java` 自带选项(设 PreferredGraphicsApi,覆盖 options 的 `"default"`)——与 `run-vanilla-vk.sh` 同一旗标,基线↔mod 同条件 |
| `--vulkanValidation` | 26.2 `Main.java` 自带选项(实例层加 VK_LAYER_KHRONOS_validation)——基线脚本同款 |
| `serve-mappings.sh` | 26.2 不混淆 → 官方 version manifest 无 client_mappings → 自定义 manifest + 恒等映射必须经 http 喂给 loom(下载器拒 `file://`,gradle.properties 注释);阶段一补丁链 |
| `VK_LAYER_PATH` 补丁 manifest | 阶段二 VVL 修复:游戏 `VulkanInstance.java:43` 钉 instance apiVersion=VK1.2,系统 VVL manifest(1.4.341)被 loader 过滤 → `run-vanilla-vk.sh` 生成 api_version 1.2.0 私有 manifest,mod 跑法直接复用 |
| `JAVA_HOME`/`GRADLE_USER_HOME` | 本机事实:Temurin 25 与全部 loom 缓存都在 `.gradle-user-home/`(工作区内),沙箱/宿主同一套 |

## 失败形态速查

| 症状 | 大概率原因 |
|---|---|
| 控制台 `Pipeline is not valid (may contain invalid shaders?)` | shader 未进资源(检查 mod 是否被加载)/ GLSL 报错看 `Couldn't compile ... shader` 行 |
| `Failed to load required shader programs` | 我们的 pass 只在首帧懒编译,若首帧就炸看上一行具体 shader |
| 世界正常但没墙 | 朝向不对(找 -X);或 `far_terrain` pass 没挂(mixin 失效 → 控制台应有 `@Inject` 报错);或云又开了 |
| 墙在但被云糊住 | renderClouds 没生效(游戏退出会覆写 options.txt —— 下次开跑前重查 `mod/run/options.txt`) |
| 启动即退 | `--graphicsBackend` 没传到 / VVL manifest 路径错(看 `requested but not found`) |
