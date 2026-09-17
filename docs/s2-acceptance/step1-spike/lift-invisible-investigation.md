# 上下文交接：远带（贪心网格）"悬浮纸片" 平原目验不可见问题

- 日期：2026-09-16（深夜 run71-76 连续排查）
- 状态：**未决**。机制层（descriptor heap / 管线 / 秒表 / push-cancel）已由合成环 A/B 验活；"纸片为何在目验机位不可见"未闭环。
- 交接：agent 侧（白川栞）→ 用户（技术决策方接管排查）

## 1. 问题陈述

贪心远带在平原（钉点 1659,320）几何整体抬升 40 块（DHVK_MESH_LIFT=40，纸片顶面 y=108、
高出草海 40 块）后，用户按指定机位（纸心正上方 22 块低头 / 侧视 1520,140,320 / 出生点仰视）
目验仍报"没有任何东西"。机制 A/B（合成调试环 DHVK_SYNTRING=1 + NDC 探针板）证明堆/管线/
draw 全链路活着（run74 彩虹墙+探针板同屏可见）。纸片不可见的最终原因未定。

## 2. 系统背景（最小集）

- 远 pass 几何 = CPU 贪心网格（3×3×3 32³ tile 带）→ 16B AoS VBO + u32 IBO → 每帧 memcpy
  上传官方 VulkanTransientMemory 瞬态 ring（双 slice，3 帧保留窗）→ 堆描述符重写 BDA →
  drawIndexed（IndexType.INT）。
- 堆表 = VK_EXT_descriptor_heap 手术（S1 闭合资产）；每帧整表 bind 一次。
- 秒表 = run64 三连修后的 CBU 侧 reset + 每 in-flight 帧查询对（已定谳，勿再动）。
- 远带**无碰撞**：玩家可穿过纸面（run76 实锤：130→101 穿纸坠地）。

## 3. 已验证事实（均有日志锚点）

| # | 事实 | 锚点 |
|---|---|---|
| F1 | 世界出生点 = (x∈[1600,1632), z∈[320,352))，正落在钉带 x[1600,1696]×z[288,384] 肚皮内 → /tp 回出生点 = 站在纸中央 | run71: band origin=(1600,0,320) |
| F2 | 平原 surfaceY=68、方块仅 y≤69、带窗 [0,96] → 纸片顶面与草海严格共面 | run71-76 Y anchor 行 |
| F3 | 共面 + 远 pass 在后 + LESS → 侧视纸片被草地逐像素盖掉（正确遮挡，run57 用户钦点的"遮挡没问题"本体） | 深度语义 |
| F4 | run72 LIFT 曾实现为窗口平移（[32,128]）→ 只削底段、顶面仍共面 → 仰视纯天；已修正为几何顶点平移（commit 038e15a） | run72 bandY=[32,128] |
| F5 | run73 几何平移后纸片 y[40,109]、顶面 108 高出草海 40 块，构建/上传/VVL 全正常（202441 verts） | run73: geoLift=40 |
| F6 | 机制活体：run74 合成环 + 探针 → 彩虹墙 + 左粉右蓝探针板同屏可见（用户截图） | run74 synth=true |
| F7 | 官方云 pass 在远带之后绘制，云直接盖住远带几何（run74 截图彩虹墙被灰云局部遮挡） | run74 用户截图 |
| F8 | run75："/tpps" 非真实指令（agent 日志黑话）→ 玩家滞留 (-60,70,0) 离钉带 1700 块 → 带区未加载、前段无带；后段用户 /tp（y=140）后带建成（202423 verts） | run75 日志 |
| F9 | run76：机位 (1648,130,336) → 玩家穿纸坠落（130→101，远带无碰撞）→ 带建成 22:24:45 → 关窗 | run76 日志 |
| F10 | 全程 VVL 标准档双零、无崩溃、干净退出（run71-76） | 各 run 日志 |

## 4. 已证伪假设

1. pushDescriptors 冲掉堆表（用户假设）→ run74 墙+板双可见证伪（现状代码 roster 有效；
   该假设的原始出处 run27-31 全黑根因属实，但解法 run32 已闭合）。
2. 纸片未构建/未上传 → 各 run 建带行 + verts 数证伪（F5/F8/F9）。
3. LIFT 窗口平移语义错 → run72 实锤并修复（F4）。
4. 出生点=纸肚中 → 属实（F1）但属机位问题，run76 已换纸心正上方。

## 5. 存活假设（按嫌疑度）与下一实验矩阵

- **H1（头号）云盖**：run73 仰视场景 = 纸顶 108 在玩家上方 39 块、云（~150）再其上且后画
  → 纸天花板被云整个糊住（F7 提供云盖机制实锤）。验证 = 关云 + 仰视/纸心正视，一局收工。
- **H2 机位/视角/时机**：run76 穿纸坠落只给了一瞬（130→69 约 2 秒）；run73 用户看的是
  地平线方向而非纸所在扇区。验证 = 高位停稳（y≥130，建议开创造/飞行或 /tp 后立刻低头）
  锁定纸面 ≥5 秒再判。
- **H3 纸片本体验**（H1/H2 排除后）：交错 +lift 顶点正确性 / 堆表 BDA 重写 / firstIndex
  换算 / u32 IBO 逐段 dump 对拍 CPU 真值（run24 表检查、run28 mv 行机制可复用）。
- 判别开关：DHVK_SYNTRING=1（机制 A/B）/ DHVK_NOWALL=1（pass 全关原生对照）/
  DHVK_NOPROBE=1（去探针色板干扰）。

## 6. 关键代码落点（mod/src/main/java/dev/dhvk/）

- FarTerrainRenderer.java：render() ~L399-435；streamFrame L483（greedy/synth 分流）；
  meshFrame L532；buildMeshBand L604（Y 锚 L622-626，交错 L687-695，+lift 在 L689）；
  bandRegionReady L714（r256 九探针 FULL 门槛，钉带模式绕带中心自同步）；
  excludeNearField L751（半径 64，跟相机）；ensureHeapSurgery/dhvkBindHeap ~L1040+。
- DhVkClient.java：全因子 + 门禁日志；DhvkCommandEncoder.java + 两个 probe mixin
  （wrapper/后端两层，新增接口方法必须两层都补）。
- FarPassStopwatch.java：秒表三连修后的形态（CBU 侧 reset 在 createRenderPass 之前）。

## 7. 坐标 / 参数速查

- 钉点 1659,320；带盒 x[1600,1696] z[288,384]；lift=40 → 纸片 y[40,109]。
- 出生点 ≈ (1615,69,335)；合成墙 A (-100, z±16, y56..108)、墙 B (-2000)。
- 雾 RD32：end=512/start=448；depthFar=2048。设备 5090，堆上限 32MiB（R-c 已定死
  单带 8.1MB/帧，3 帧 24.7MB，裕量 22% —— rc-budget.md）。

## 8. 复现配方

    export JAVA_HOME=$PWD/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2
    export PATH=$JAVA_HOME/bin:$PATH
    export GRADLE_USER_HOME=$PWD/.gradle-user-home
    # mappings 服务须活: 127.0.0.1:8726 (mc-src/scripts/serve-mappings.sh)
    ./gradlew --stop
    DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40 ./gradlew :mod:runClient \
      --args="--graphicsBackend vulkan --vulkanValidation" \
      2>&1 | tee /tmp/s2v2-run-<name>-$(date +%H%M%S).log
    # 全开档: 加 VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile

VVL 判读：grep -c -i 'VALIDATION ERROR' 与 grep -c -E 'vkCmd|vkQueue' **两者都要为 0**
（26.2 的 VVL 查询/present 类报错不走 'VALIDATION ERROR' 字面）。
游戏内指令只有 /tp（无 /tpps）：/tp @s <x> <y> <z>。

## 9. run 表（本问题段）

| run | 配置 | 结局 |
|---|---|---|
| 71 | 无钉无 lift（退役烟测） | VVL 双零/干净退出；spawn=纸肚中实锤 |
| 72 | 钉+LIFT 窗平移 | "没有任何东西"（F4） |
| 73 | 钉+LIFT 几何平移 | "还是看不见"（F5；用户提 push 假设） |
| 74 | 合成环+探针 | 墙+板双可见（F6/F7）→ 机制活体，云盖实锤 |
| 75 | 钉+LIFT | /tpps 事故（F8）；带后段建成，未及目验 |
| 76 | 钉+LIFT+纸心机位 | 穿纸坠落（F9）；带建成，关窗 |

## 10. 已知形态 / 无害噪声

- stream budget 1000µs 对 4MB 级带超限（report-only，Task 2/3 重标定）。
- 纸片上下切面白面；平涂 tint（Task 3 贴图）；树=圆点（Task 2 LOD）；远带无碰撞。
- Realms 401 / SignedJWT 噪声（离线认证，非故障）。

## 11. 交接状态

- 工作树：干净（run76 已关窗，无后台 run）；映射服务保活。
- 提交链：87a50b1（过闸）→ 45b3911（高度场退役）→ 6d1ca51（LIFT 因子）→
  038e15a（LIFT 语义修正）→ 本档（investigation 交接）。
- 用户下一手建议先做 H1（关云一局）与 H2（高位停稳一局），若仍不可见再进 H3 的
  顶点/BDA dump。agent 侧随时可点火（说"跑"即可）。
