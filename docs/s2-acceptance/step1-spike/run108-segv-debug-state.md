# 状态锚点 (2026-09-17 01:25, 压缩后继续用)
## 已通过的验收
- run116 (信标+full VVL+同构扇pass): 双零 + 君目视红碑 ⇒ S2/S3 移植验收通过 (commit 链见 git log, 未推送)。
- SEGV 五连根因=帧首ring staging 污染 (裸 DhVkRawUploader 通道解决); NVIDIA Xid109/31=颜色only扇pass隐式布局死锁 (扇pass附主目标深度视图同构化解决)。GPU=NVIDIA 615.71 @ PCI 01:00。
## 进行中: 真实带验收 (君: "没东西大哭")
- run117 (real band, full VVL, 600s): 零SEGV/异常, 但带没建 → 原因1: bandRegionReady 九探针 (带中心±256/±181 的 chunk 全要 FULL) 在默认渲染距离下外层探针够不着; 原因2 (full VVL 新抓): vkCmdUpdateBuffer dst 缺 TRANSFER_DST → run118 已修 (createStagedBuffer 裸路径 usage|USAGE_COPY_DST, 已提交)。
- **现场解法 (无需重启, run117窗口若还活着)**: 游戏内 F3→Video Settings→Render Distance 拉满 (≥~17ch 覆盖 ±256 探针), 几秒后带自动构建 (HEAD窗每帧重试)。
- 若窗口已死: 重跑 = cd /home/sunny/Documents/dh-vk2026 && env 三件套 + VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile + DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40 timeout 600 ./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation"
## 关键坐标/门限
pin(1659,320) 带区 x[1600,1696] z[288,384] surfaceY=68 lift=40; 观景点 (1648,130,336) 朝北; 信标 DHVK_BEACON=1 x[1571,1747] z=248 y[40,320]; fog 448/512, depthFar 2048, 离屏 854×1363。
## 环境
gradle env 三件套每次必带; ./gradlew --stop 先停; JDK25 .gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2; mappings 服务 127.0.0.1:8726 (bash-1 job 保活); 二分门 DHVK_NOBAND/NOFAN/NOPASS/NOSTAGE/NOOFFSCREEN + DHVK_RINGSTAGE 回退; coredumpctl 有全部 core。
## 之后 (S4)
- 真实带双零+君地平线截图 → VVL standard 档复核 → 清理死代码 (surgery/探针/静态映射) → README LGPL-3 (DH main@f5d2f80, Vulkan@64d8f7e; jeseibel core@64c5d96/269f2c3) → rc-budget 16B 重推 → 推 run87→118 段提交。
