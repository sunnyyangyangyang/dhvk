# 状态锚点 (2026-09-17 01:55, 压缩后以本文件为准)
## 已通过验收
- S2/S3 移植验收: run116 (信标+fullVVL+同构扇pass) 双零+君目视红碑。
- 已清根因四层: ①帧首ring staging污染→裸DhVkRawUploader通道 ②LWJGL fork栈struct不可close(jemalloc SEGV) ③NVIDIA Xid109/31=颜色only扇pass隐式布局死锁→扇pass附主目标深度视图同构化 ④探针定谳(本段新): 本LWJGL fork×JDK25 下 MemoryUtil.memAddress 对JDK直接/堆缓冲恒=0 (jdk-direct=0x0 heap=0x0 唯memAlloc合法) → run118/119 SEGV=裸vkCmdUpdateBuffer按src=0下发,驱动memmove空指针(si_addr=0x10=0+16B向量步长) → run120修: 源头一律先拷入 MemoryUtil.memAlloc(size) 自管缓冲再上传(已提交)。
- GPU=NVIDIA 615.71 @PCI 01:00 (不是mesa radeon)。
## 进行中: 真实带验收 (君休息中, 待续)
- run120: 全程零崩溃零VVL (memAlloc修复生效), 但带门(±256 FULL探针)等满3000帧未过 → 只见16B桩。
- run121 (DHVK_BANDFORCE=1 强建): 零崩溃零VVL, **mesh band built**: origin=(1632,-128,320) verts=31 idx=36 — 带建出但轮廓稀疏(强建时大量区块未FULL, 贡献空气); 窗口在君休息前已关。
- **君回来后续**: 重跑 run121 同款命令 (BANDFORCE) 或渲染距离拉满跑正门; /tp 1648 130 336 朝北看带 → 截图验收 → VVL standard 复核 → S4。
- 命令: cd /home/sunny/Documents/dh-vk2026 && env三件套 + VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile + DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40 [DHVK_BANDFORCE=1] timeout 900 ./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation"。
## 环境
gradle env三件套每次必带(JAVA_HOME=$PWD/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2, PATH, GRADLE_USER_HOME=$PWD/.gradle-user-home); --stop先停; JDK25; 二分门 DHVK_NOBAND/NOFAN/NOPASS/NOSTAGE/NOOFFSCREEN/RINGSTAGE; lwjgl core natives jar=.gradle-user-home/caches/.../org.lwjgl/lwjgl/3.4.1/2734e106bd95db49fe0da8cf8bf7fd96475c436c/lwjgl-3.4.1-natives-linux.jar; 探针 /tmp/memprobe.java (javac+java, cp=core jar+natives jar); mappings服务127.0.0.1:8726(bash-1); coredumpctl有core。
## 之后 (S4)
真实带双零+君截图 → VVL standard 复核 → 清死代码(surgery/探针/静态映射) → README LGPL-3 (DH main@f5d2f80, Vulkan@64d8f7e; jeseibel core@64c5d96/269f2c3) → rc-budget 重推 → 推 run87→run120 段提交。