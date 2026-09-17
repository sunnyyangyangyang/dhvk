# 状态锚点 (2026-09-17 02:05)
## 已锁定
- S2/S3 移植验收通过 (run116 信标 fullVVL 双零+君目视红碑)。
- 五层根因全清: ①ring staging污染→裸DhVkRawUploader ②LWJGL fork栈struct不可close ③NVIDIA Xid109/31=扇pass颜色only隐式布局死锁→同构附件 ④memAddress对JDK缓冲恒0→源头先拷MemoryUtil.memAlloc ⑤BANDFORCE抢建稀疏带冻结→自然门限(91帧即开, 202k顶点完整带, run131/132)。
- **全量日志解锁 (君要求)**: 26.2客户端日志链=slf4j→log4j-slf4j2→Log4j2(2026重建版log4j-core, LevelFilter/AbstractFilteredAppender已删)。DHVK_FULLLOG=1 开关 (run133, DhVkClient.dhvkEnableFullLogging): core.Logger是Supplier<LoggerConfig>, AppenderRef重写(level=DEBUG,filter=null)+rootCfg.setLevel(DEBUG)+ctx.updateLoggers()。run134验证: appenders=[DebugFile SysOut LatestFile ServerGuiConsole] 全切DEBUG, 84行DEBUG实锤。
## 待办 (君醒来)
0. **run136 已验证**: staging 通道后 dataSize>65536 VVL 报错绝迹, 3.2MB VBO/789KB IBO 完整上卡 (run135 修: 大缓冲分块updateBuffer灌staging+vkCmdCopyBuffer搬; fork绑定=vkCmdCopyBuffer(cbuf,src,srcOff,VkBufferCopy.Buffer无sType字段), 提交)。带几何 202k 顶点。剩 = 君地平线目视 (渲染距离拉满)。
   ⚠ 旧 STREAM_BUDGET=1000us 门对一次性大上传必 FAILED 日志 (无害, S4 再重定预算/删门)。
1. 真实带目视验收: 点火 (env三件套+VK_LAYER_SETTINGS_PATH=full+DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40 [+DHVK_FULLLOG=1]) → 君 F3渲染距离拉满→/tp 1648 130 336 朝北 → 地平线体素带截图。带构建已证实完整(202207 verts, 91帧)。
2. VVL standard 档复核 (full档双零已多次达成; 注意 standard 档会在退出时吐 vkDestroyDevice 未释放对象告警: dhvk缓冲/离屏 — S4 需加设备拆除时释放钩子)。
3. S4: 清死代码(surgery路径/探针mixin的fulllog探针行/BANDFORCE) + README LGPL-3 (DH main@f5d2f80, Vulkan@64d8f7e; jeseibel core@64c5d96/269f2c3) + rc-budget重推 + 推 run87→run134 段 (远端 sunnyyangyangyang/dhvk, 上次推至 9a683c4..cf3ecf1 之前的段已推, 注意再推增量)。
## 环境
gradle env三件套; --stop先停; JDK25; lwjgl core natives=.gradle-user-home/caches/.../org.lwjgl/lwjgl/3.4.1/2734e106bd95db49fe0da8cf8bf7fd96475c436c/lwjgl-3.4.1-natives-linux.jar; slf4j-simple探针jar=.../slf4j-simple/2.0.18/503354e24.../ (游戏里实际不走它!); log4j-core=2.26.1 (2026-06重建版, 新API: core.Logger.get()=LoggerConfig, AppenderRef仅getRef/getLevel/getFilter); mappings服务127.0.0.1:8726; coredumpctl有core; 长窗口跑用 run_in_background 别前台(前台超时会吞输出)。