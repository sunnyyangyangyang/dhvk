# 状态锚点 (2026-09-17 02:2x, T4)
## 谜题: 带建出+pass全执行, 屏幕不可见 (君run138/141两次"没看见/窗口死了")
- run141 诊断日志定谳管线全通: band pass 每帧 draw(idxCount=202134, 官方DT切片 offset轮换✓, vbo=3234080B✓) + fan pass onto main 854x1363✓ + fullVVL双零 + 0SEGV。
- 几何: surfaceY=68 lift=40 → 带顶面y=108(君130下方22); 整片纯红; 红方尖碑塔 y[108,168] 钉点(1659,320)=君**西偏北**19块(注意+x=西! 之前口头说"东北"是口误, 塔在左手边)。
- 信标(远墙)可见→链通; 带+塔(近处/相机在体积内)不可见 ⇒ 未解。候选: ①塔方向口误君没看对地方 ②相机在抬升地形内近裁/背面剔除 ③扇discard边界。
- run139/140 死窗=沙箱容器退场连杀子进程(非游戏崩溃) → 点火必须 run_in_background 长驻容器。
## 现场
- run142 长驻窗口在跑 (job bash-94, fullVVL+FULLLOG+lift40+mesh_at), 君暂停要"看看log"。
- 下一步(君定夺): 让君进run142窗口看**左手偏北的红碑+下半屏红板**; 或做离屏读回dump定谳带pass输出内容; 或 DHVK_TOWER alone(lift=0)对照。
## 环境
env三件套+VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile; 点火: DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40 DHVK_FULLLOG=1 timeout 900 runClient (长驻容器!); pass诊断日志=每300帧debug(band draw参数/fan目标), FULLLOG放行; 探针行仍在 MixinLevelRendererDhvk (S4清)。
## S4 待办
拆除钩子 + STREAM_BUDGET重定 + 清死代码/探针 + README LGPL-3 (DH main@f5d2f80/Vulkan@64d8f7e; core@64c5d96,269f2c3) + rc-budget + 推增量段(run122→run142, 远端sunnyyangyangyang/dhvk)。