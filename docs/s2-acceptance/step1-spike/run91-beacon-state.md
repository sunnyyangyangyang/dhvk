# run91 信标墙状态 (compaction 锚点)
- 用户裁定: 巨墙找不到 → DHVK_BEACON=1 信标石碑: 176宽(x1571..1747)×280高(y40..320), z=248平面, 纯红双面(cull off), 观景点(1648,130,336)正北88块。/tp 1648 130 336 朝北(-z)正对碑面。
- 代码: DhVkClient.beaconOn() (DHVK_BEACON); buildMeshBand 开头早退分支建 4 顶点/6 u32 索引; 其余管线不变 (dhStyleFrame 离屏+合成扇)。
- 已落地: S2 离屏对+TAIL钩子+PIPELINE_DH (run89/90); S3 apply_fan 合成扇 (run90); DH jar 停 refs/dh-test-jars/; mods 目录空。
- run90 结果: 用户截图=纯dhvk? 否 — run89截图是DH; run90 已纯dhvk(用户反馈"巨大墙找不到"=看到了几何但形态不好找) → 信标墙。
- 下一步: run91 (DHVK_BEACON=1) 用户地平线视觉验收纯红石碑; VVL标准层双零; 过后 S4 清理+README LGPL-3+推送。
- 环境配方: env 三件套 + ./gradlew --stop + DHVK_BEACON=1 ./gradlew :mod:runClient --args="--graphicsBackend vulkan --vulkanValidation"。
