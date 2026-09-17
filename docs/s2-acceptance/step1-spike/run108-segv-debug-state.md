# SEGV/Xid 调试状态 (压缩锚点, 2026-09-17 01:15)
## 已定罪链 (run110-115 判罪矩阵, 全部 timeout 自动杀实验)
- 对照(静音mod)干净; A/B(带/扇)+staging 炸; C(NOSTAGE)干净; D(staging+清屏)炸; E(纯缓冲staging)炸
  ⇒ 毒源 = 帧首对官方瞬态 ring 的缓冲 staging (createBuffer(ByteBuffer)→uploadStaging→MappedView memCopy 持脏引用 SEGV)。
- run111: 裸上传通道 DhVkRawUploader (专属pool+一次性CBU+复用fence, vkQueueSubmit2KHR 同队列FIFO) 全替换 ring;
  崩点=栈上struct被close()误走nmemFree→jemalloc SEGV (LWJGL fork 陷阱, 官方惯用法=struct不close)。
- run113/114: SEGV 全灭。run114(NOFAN 带pass only) 150s 零异常零新Xid = 裸栈+带pass 干净。
- run115(只扇,空离屏) 炸: 颜色-only 扇 pass 写主目标 → NVIDIA Xid 109(CTX SWITCH TIMEOUT) + Xid 31(MMU FAULT_PDE VIRT_READ)。
  **GPU 是 NVIDIA 615.71 (PCI 01:00), 不是 mesa radeon**; 9/16 23:24 旧栈 run 也有同款 Xid ⇒ GPU 侧故障贯穿全程, CPU SEGV 或为次生。
## run116 (当前): 扇 pass 附主目标深度视图 (与场景 pass 附件集同构 → 免隐式布局转换死锁假设)
- 君建议: 本 run 起 VVL 升 full profile (VK_LAYER_SETTINGS_PATH=$PWD/mc-src/scripts/vvl-fullprofile)。
## 文件
- mod/src/main/java/dev/dhvk/DhVkRawUploader.java (裸通道); FarTerrainRenderer: createStagedBuffer/DHVK_RINGSTAGE 回退门,
  二分门 DHVK_NOBAND/NOFAN/NOPASS/NOSTAGE/NOOFFSCREEN; 扇pass=renderFanPass(颜色+深度同构附件, run116)。
- 信标: DHVK_BEACON=1, /tp 1648 130 336 朝北(-z); 观景点正北 88 块, 碑 x[1571,1747] z=248 y[40,320]。
## 验收链
run116(full VVL) 双零+君截图红碑 → 真实带 (DHVK_MESH_AT="1659 320" DHVK_MESH_LIFT=40) → S4 清理+README LGPL-3(DH main@f5d2f80/Vulkan@64d8f7e; jeseibel core@64c5d96/269f2c3)+推 run87→116 段提交。
## 若 run116 仍炸
- full VVL 报错定位 → 按报错修 (布局/描述符/采样器);
- 备选: 扇 pass 目标改专用复合纹理+官方copyTextureToTexture(丢深度测试, 慎); 或帧内顺序改扇在场景前(视觉不对, 慎)。
