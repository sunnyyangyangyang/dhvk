# dh-vk2026 设计文档:MC 26.2 完整反混淆源码 + Fabric Mod 开发地基

- 日期:2026-09-13
- 状态:已与需求方逐节确认(§4-§6、§8-§9 对应聊天中的设计第 1-4 节,全部获批;
  2026-09-13 需求方追加 §7 mod 开发阶段指导原则)
- 目标:为"类 Distant Horizons 的 Vulkan 渲染 mod"开发铺好完整地基

## 1. 背景与目标

需求方要在 Minecraft 26.2(2026-06-16 release,Java 25,新一代 Vulkan 渲染引擎)上做
类似 Distant Horizons 的渲染 mod。本阶段交付三件事:

1. **完整反混淆源码**:26.2 的 client + server 全部反混淆为带官方命名(mojmap)的
   可读 Java 树,含 Vulkan(vk)渲染器部分,独立落盘可翻阅;
2. **Mod 脚手架**:Fabric + Loom 工程,JDK 25 工具链,可编译、可真机 runClient;
3. **CLI linter**:`./gradlew lint` 一条命令,严格/宽松双档,调教到全绿并做过负向自检。

后续阶段(不在本次范围):mod 的正式功能开发(距离渲染、LOD 地形、vk 渲染管线接入等)。

## 2. 环境侦查结论(2026-09-13 实测)

| 项 | 结论 |
|---|---|
| MC 26.2 | release;javaVersion majorVersion 25(component java-runtime-epsilon);mainClass net.minecraft.client.main.Main |
| client.jar | 39,193,383 B,sha1 `2dc72797acbc1b63fc16a11c4ac393605f453754`,piston-data.mojang.com |
| server.jar | 60,894,273 B,sha1 `823e2250d24b3ddac457a60c92a6a941943fcd6a`,同上 |
| libraries | 131 个,含 LWJGL 3.4.1 全家桶 + lwjgl-spvc / lwjgl-shaderc(SPIR-V 工具链)→ **Vulkan 渲染器打包在 client.jar 内,无独立 jar** |
| mapping | 26.2 version JSON 未单列 mapping 下载项,预计官方 mojmap 随 client.jar 内置(1.21.5+ 传统);实现时解 jar 验证,否则回退 Mojang mapping 发布通道 |
| 机器 | 32 核 / 60G RAM / 1.3T 空闲 /tmp 30G tmpfs;GPU = NVIDIA RTX 5090 (GB202) + iGPU,wayland 会话 |
| 沙箱限制 | bwrap 沙箱内无 /dev/dri(runClient 需在宿主终端跑);dnf 在沙箱内 HOME 只读,系统级安装需出沙箱 |
| 工具链 | 无系统级可用 JDK(java-21 目录是空壳、jre-25 是残软链,均无 javac);无 gradle;python 3.14.7;git 2.55 |
| 工作区 | /home/<user>/Documents/dh-vk2026,开工前为空目录 |

## 3. 需求决策(用户逐项确认)

| 决策点 | 结论 |
|---|---|
| Loader | **Fabric**(+ Loom);与 Distant Horizons 同轨 |
| Linter 形态 | **只要 CLI 静态分析**(`./gradlew lint`);IDE 语言服务不做 |
| 源码范围 | **client + server 全量** |
| 管线方案 | **方案 A**:Vineflower 独立源码树 + Loom 脚手架 + source jar 本地发布(不做版本化制品仓,结构上预留升级路径) |
| JDK 供给 | **foojay-resolver 自动拉 Temurin 25**(工作区自包含;备选 dnf openjdk-25 未选) |
| Mod 身份 | mod id `dhvk`,包名 `dev.dhvk`,集中 `gradle.properties` |
| 探针 mixin | **保留**(验证 mixin 管线 + 示范插桩姿势) |

## 4. 仓库结构(设计第 1 节,已批)

```
dh-vk2026/                        ← git 仓库 + gradle 多工程根
├── settings.gradle / build.gradle ← 根工程:子工程 = :mod + :mc-src
├── gradlew + gradle/wrapper/      ← wrapper(版本钉死)
├── lint/                          ← 两套 Checkstyle ruleset
│   ├── checkstyle-mod.xml         ← 严格档(:mod)
│   └── checkstyle-refsrc.xml      ← 宽松档(:mc-src)
├── mc-src/                        ← 【源码轨交付物】
│   ├── client/                    ← client.jar 反混淆全树(common + client + vk 渲染器)
│   ├── server/                    ← server.jar 反混淆后剔除与 client 重复的类(服务端专属)
│   ├── mappings/                  ← 实际使用的 mojmap(留底,重编译输入)
│   ├── artifacts/                 ← 原始 client/server jar(gitignore,sha1 钉在脚本里)
│   │   └── *-sources.jar          ← 反混淆产出的 source jar
│   └── scripts/
│       ├── decompile.sh           ← 一键重跑:Vineflower(版本+sha256 钉死)+ jar(sha1 钉死)+ mapping
│       └── verify-vk.sh           ← vk 完整性自检:vulkan 渲染器包全量类清单 + 计数 + 抽样
├── mod/                           ← 【Mod 轨】fabric-loom 工程
│   ├── build.gradle(子工程,由根 settings.gradle 的 include 纳入)
│   ├── src/main/java/dev/dhvk/    ← 入口 + 探针 mixin
│   └── src/main/resources/        ← fabric.mod.json、mixins.json
└── docs/superpowers/specs/        ← 本文档
```

git 策略:源码树、脚本、mappings、规则集入库;原始二进制 jar 与构建产物 gitignore
(jar 可随时按钉死的 sha1 重下)。

## 5. 源码轨管线(设计第 1 节,已批)

1. **下载** client.jar / server.jar(piston-data.mojang.com,URL 与 sha1 见 §2),
   校验 sha1 通过才进入下一步;
2. **mapping**(2026-09-13 修订:用户确认 MC 26.1 起代码不再混淆,官方命名
   直接存在于字节码)→ mapping 文件不再是反混淆硬依赖:原"解 jar 验证内置
   mojmap/回退 Mojang 通道"步骤免除,改为在 Task 4/5 对反混淆产物做命名健全性
   验证(grep `p_`/`f_` 残留 + 抽样);
3. **反混淆**:Vineflower(GitHub 最新 release,版本号 + sha256 钉进 `decompile.sh`),
   **classpath 挂载 26.2 全部 131 个依赖库**(直接取 version JSON 自带下载 URL,
   不走 maven 解析)——有 classpath 时类型推断正确,代码质量显著更好;
4. **双树策略**:`mc-src/client/` = client jar 全量反混淆(权威);
   `mc-src/server/` = server jar 全量反混淆后**剔除与 client 重复的类**
   (common 代码以 client 树为准,server 树只留 dedicated server 专属部分);
5. **vk 验证**:`verify-vk.sh` 列出 vulkan 渲染器相关包的全部类、计数,并抽样
   核心类文件展示——证明 vk 部分一个不缺;
6. **source jar 发布**:两棵树各打 source jar,安装到本地 maven 仓库(`~/.m2`),
   坐标 `dev.dhvk:mc-26.2-client-sources` / `dev.dhvk:mc-26.2-server-sources`,
   供任何项目一行依赖挂命名源码;
7. **可复现**:`decompile.sh` 全程幂等(输入 sha1/版本全钉死),本次运行即脚本的
   端到端验证。

## 6. Mod 脚手架(设计第 2 节,已批)

- **Loom**:取 Fabric maven 上支持 26.2 的最新稳定版,钉在 `gradle.properties`
  (实现时实查版本列表定死);Loom 自身构建期反混淆也指到 **Vineflower + 官方
  mojmap**,与独立源码树同一工具同一命名;
- **JDK 25**:settings.gradle 挂 foojay-resolver,toolchain 25 自动拉 Temurin;
  系统零改动;
- **Fabric API**:实现时查 26.2 有无对应版本——有则依赖进去,无则纯
  minecraft + mixin 骨架并在 README 注明;
- **骨架**:`ClientModInitializer` 入口 + 一个探针 `@Mixin` + `fabric.mod.json`
  (指向 26.2)+ `mixins.json`(client 侧)。探针 mixin 按 §7 原则 3(原生句柄)
  设计:hook 渲染器初始化路径,**用 LWJGL Vulkan 拿到官方实际在用的
  VkDevice/VkQueue/VkCommandBuffer 等底层句柄**,存入 mod 侧 accessor 类
  (`dev.dhvk.VkHandles`)并打进日志——同时验证 mixin 管线可用、以及
  "mod 只依赖 native 句柄、不碰 Mojang 包装类"这条架构在 26.2 上走得通;
- **runClient**:沙箱无 GPU,交付为宿主终端命令写入 README(5090 + Vulkan
  渲染器);沙箱侧以 `./gradlew :mod:build` 全量编译通过为工具链最强静态证据。
  README 的 runClient 配置**必须默认开启 Vulkan Validation Layer**(§7 原则 1);
  具体开关形态(视频设置项/config 文件/启动参数)实现时从反混淆源码中挖出
  并写死进 README。

## 7. Mod 开发阶段指导原则(用户指定,2026-09-13)

下一阶段(LOD 渲染实际开发)的指导原则,由需求方 2026-09-13 指定;
本阶段地基必须为 1-3 条预留钩子,对 4-5 条必须忍住不预铺:

1. **开启 Vulkan Validation Layer(绝对必要,100%)**——不开校验层时 Vulkan 错误
   是"静默崩溃"(窗口闪退/黑屏/GPU Hang,控制台零报错),开了则直接指出哪行
   代码、哪个 VkBuffer 访问冲突。地基落点:README 的 runClient 配置默认开启,
   开关形态实现时从反混淆源码挖出(见 §6 runClient 条)。
2. **深度格式与投影矩阵对齐(绝对必要,100%)**——LOD 远景必须被原版前景正确
   遮挡:mod 管线必须精确对齐 26.2 官方深度格式(D32_SFLOAT 还是
   D24_UNORM_S8_UINT?Reverse-Z 与否?)与投影矩阵约定(原点/NDC 范围)。
   地基落点:新增交付物 `docs/mc26.2-vk-reference.md`(验收项 E9)——从反混淆
   的 vk 渲染器代码中挖出深度格式、投影矩阵约定、swapchain/帧提交结构、
   native 句柄获取 hook 点,全部带源码引用。
3. **原生句柄优先于官方包装类(强烈建议,70%)**——拿到底层 VkDevice/
   VkCommandBuffer 指针后用 LWJGL Vulkan 自建 mod 侧 Pipeline,不依赖
   Mojang 内部包装类(防小版本更新重构包装类导致全线报红)。地基落点:探针
   mixin 即按此架构实现(见 §6 骨架条)。
4. **多线程录制/Compute 剔除(前期 0%)**——前期不碰 Secondary Command
   Buffers 与 Compute culling;单线程 Draw 即可(5090 单线程 5000 次
   vkCmdDrawIndexed 无压力)。地基落点:不预铺任何多线程脚手架。
5. **SPIR-V 自动编译(前期 0%)**——前期不写 glslc/shaderc 的 gradle task;
   土法:手写 .vert/.frag,终端 `glslc shader.vert -o shader.spv`,二进制
   进 resources/ 直接读。地基落点:不接 shader 编译自动化;机器上
   glslc/glslangValidator 的可用性在下一阶段开工时核查并记入参考文档。

## 8. Linter 设计(设计第 3 节,已批)

- **任务形态**:根任务 `./gradlew lint` 聚合 `:mod:lint`(严格档)与
  `:mc-src:lint`(宽松档);
- **:mod(严格档)= ErrorProne + Checkstyle**
  - ErrorProne 经 `net.ltgt.errorprone` 插件接入,JDK 25 配标准 `--add-exports`
    参数组,抓真 bug(空指针、equals 误用、资源泄漏等);
  - Checkstyle 规则集 `lint/checkstyle-mod.xml`,风格底线;
- **:mc-src(宽松档)= 仅 Checkstyle**
  - 参考树不做编译、不跑 ErrorProne(反编译器产物里的合成成员会制造误报);
  - `lint/checkstyle-refsrc.xml` 规则集在 ~1 万真实反混淆文件上**调教到零误报**——
    规则不是抄模板,是被真实代码喂出来的;
- **验收仪式(负向自检)**:
  1. `./gradlew lint` 双目标全绿;
  2. 故意种一个 ErrorProne 必抓坑 + 一个 Checkstyle 必抓坑 → `lint` 必须红;
  3. 拔掉违规 → 重新 `lint` 必须绿。红过又绿 = "调试好"的实证。

## 9. 验收标准(设计第 4 节,已批)

| # | 验收项 | 证据形态 |
|---|---|---|
| E1 | 下载完整 | 两 jar sha1 与官方一致;Vineflower 版本 + sha256 钉死 |
| E2 | 反混淆质量 | 两树类计数;混淆残留(`p_d+`/`f_d+` 标识符)grep 零命中;抽查入口/渲染器/vk 核心类为正常官方命名 Java |
| E3 | vk 完整性 | `verify-vk.sh` 输出 vulkan 包全量类清单 + 计数 + 抽样 |
| E4 | 工具链可编译 | `./gradlew :mod:build` 全绿(骨架含探针 mixin,对 26.2 minecraft 编译通过) |
| E5 | linter 调好 | `./gradlew lint` 双目标全绿 + 负向自检红→绿 |
| E6 | 真机可跑 | README 提供宿主终端 runClient 命令与预期行为 |
| E7 | 可复现 | `decompile.sh` 本次运行即端到端验证;重跑一条命令 |
| E8 | 落仓 | git 仓库 + spec + 全量交付物提交;工作区只留该留的 |
| E9 | vk 参考文档 | `docs/mc26.2-vk-reference.md`:26.2 官方深度格式/投影矩阵约定/swapchain 与帧提交结构/native 句柄 hook 点/Validation Layer 开关,全部从反混淆 vk 代码中挖出并带源码引用(§7 原则 1、2、3 的依据) |

## 10. 范围外(YAGNI,明确不做)

- IDE 语言服务/诊断配置(用户只要 CLI);
- SpotBugs/ErrorProne 用于参考树;
- 版本化制品管线(方案 C 的完整形态;A 已预留 source jar 发布,升级成本低);
- mod 的正式功能代码(DH 式渲染逻辑);
- mod 前期多线程录制/Compute 剔除/SPIR-V 自动编译(§7 原则 4、5);
- 沙箱内 runClient(无 GPU,宿主终端跑)。

## 11. 风险与预案

| 风险 | 预案 |
|---|---|
| Loom 最新稳定版尚不认 26.2 | 取支持 26.2 的最新稳定版;仍无则用 loom snapshot 通道并钉版本号,README 注明 |
| mojmap 发布渠道 26.x 全系失踪(已实测) | 2026-09-13 用户确认 26.1+ 不混淆 → mapping 免除;Loom 侧映射策略按 Task 7 实测切换(identity/自定义 manifest) |
| Fabric API 无 26.2 版本 | 纯骨架先交付,README 注明升级点 |
| ErrorProne 与 JDK 25 兼容问题 | 标准 add-exports 参数组;若仍炸,降级为 :mod 仅 Checkstyle 并在 README 说明(lint 全绿的承诺不破) |
| 反混淆耗时 | 单 jar 预计 10-20 分钟,32 核下 Vineflower 并行无压力 |
| dnf/JDK 系统态干扰 | 不选 dnf 路线,foojay 工作区自包含,系统零改动 |

## 12. 下载清单(开工时按全局规矩再整体确认一次)

| 下载物 | 来源 | 用途 |
|---|---|---|
| client.jar / server.jar(26.2) | piston-data.mojang.com | 反混淆原料 |
| Vineflower 最新 release jar | GitHub Vineflower releases | 反编译器 |
| Temurin 25 JDK | api.adoptium.net(foojay-resolver 自动) | 编译工具链 |
| Gradle 发行版 | services.gradle.org(wrapper 自动) | 构建 |
| Maven 依赖(LWJGL/netty/gson 等 131 库、Loom、插件) | Maven Central / Fabric maven | classpath 与构建 |
| (备选)Mojang mapping jar | Mojang 发布通道 | 仅当 jar 未内置 mojmap |
