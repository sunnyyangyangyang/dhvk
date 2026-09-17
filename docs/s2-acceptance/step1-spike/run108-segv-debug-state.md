# 状态锚点 (2026-09-17 02:25, T3 落盘)
## 已锁定 (全部有实证)
- S2/S3 信标移植验收通过 (run116)。五层上卡/上帧根因全清 (ring污染→裸通道; struct close→jemalloc; Xid109/31→扇pass同构附件; memAddress恒0→memAlloc; 64KB上限→staging+vkCmdCopyBuffer(cbuf,srcBuf,dstBuf,Buffer) run135-137)。
- 全量日志: DHVK_FULLLOG=1 (log4j2 2026重建版 API: core.Logger.get()=LoggerConfig, AppenderRef重写) run134验证。
- 带构建: 自然门限~90帧, 202177顶点/3.2MB VBO/789KB IBO 完整上卡 (run137/138 双零)。
## 当前谜题 (君: "没看见")
- run138 几何全对: surfaceY=68, lift=40 → 带顶面 y=108 (君y=130 下方22块); **红色**(lift≠0整片纯红) + 自动红色方尖碑 tower y[108,168] 于钉点(1659,320)=君东北19块、顶高出君眼38块。索引类型=INT✓ (L491)。27 tiles 全 kept。
- 信标(4顶点远墙)可见 ⇒ 离屏→扇→主目标链通; 带(20万顶点+塔, 相机在带体积内/上方)不可见 ⇒ 差异=几何尺度/相机在几何内部/带pass内容。
- 待查: renderBandPass 在带run是否真发出 draw (UNIFORM_SLICES 非空?); 扇 discard 深度采样; 相机在抬升地形体积内的近裁/背面剔除效应。
## 下一步
run139: lift=40+tower+FULLLOG+fullVVL, 加 renderFanPass/renderBandPass 的节流 debug 日志 (draw次数/切片状态), 君入窗细看下屏面与东北方; 若仍无 → 二分 DHVK_NOFAN 看离屏 (需读回) 或塔alone(lift=0)对照。
## 环境
env三件套+VK_LAYER_SETTINGS_PATH=full; 命令 DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40 DHVK_FULLLOG=1 timeout 900 runClient; 长窗口走 run_in_background; 探针行在 MixinLevelRendererDhvk (fulllog); lwjgl/log4j 2026重建版 API 差异见各 commit。
## S4 待办
拆除钩子(退出对象告警) + STREAM_BUDGET 1000us 门重定/删 + 清死代码 + README LGPL-3 + rc-budget + 推增量 (远端 sunnyyangyangyang/dhvk)。