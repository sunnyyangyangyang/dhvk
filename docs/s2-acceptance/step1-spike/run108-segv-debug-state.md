# 状态锚点 (2026-09-17 01:35)
## 已通过
- S2/S3 移植验收: run116 (信标+fullVVL+同构扇pass) 双零+君目视红碑。
- 三层根因全清: ① 帧首ring staging污染(裸DhVkRawUploader通道) ② LWJGL fork栈struct不可close(jemalloc SEGV) ③ NVIDIA Xid109/31=颜色only扇pass隐式布局死锁(扇pass附主目标深度视图同构化)。GPU=NVIDIA 615.71 @PCI 01:00。
## 进行中: 真实带 (君两次"没东西")
- run117: 带没建=bandRegionReady九探针(±256/±181需FULL)+满渲染距离; fullVVL抓到dst缺TRANSFER_DST → run118修(usage|USAGE_COPY_DST)。
- run118: SEGV新形态=__memmove_avx512@驱动vkCmdUpdateBuffer(CPU→CBU拷贝), si_addr=0x10, RDX=0x40=扇VBO64B = C2判死参数→JNI中途GC→Cleaner释放直接缓冲native内存→悬空指针。→ run119: 静态PIN[1]强引用横跨vkCmdUpdateBuffer调用。
- 君需: /tp 1648 130 336 + F3 Render Distance 拉满 (±256探针)。
## 下一步
run119 点火中 (real band, fullVVL, 900s): 看 ① 无SEGV ② mesh band built 出现 ③ 君地平线截图。之后 VVL standard 复核 → S4(清理死代码+README LGPL-3 DH main@f5d2f80/Vulkan@64d8f7e, jeseibel core@64c5d96/269f2c3 + rc-budget重推 + 推run87→119段)。
## 环境
gradle env三件套; --stop先停; VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile (full档); JDK25 .gradle-user-home/jdks/...; 二分门 NOBAND/NOFAN/NOPASS/NOSTAGE/NOOFFSCREEN/RINGSTAGE; mappings服务127.0.0.1:8726; coredumpctl有core。