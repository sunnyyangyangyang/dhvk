package dev.dhvk;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.dhvk.heap.DescriptorHeap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDescriptorMappingSourceConstantOffsetEXT;
import org.lwjgl.vulkan.VkDescriptorMappingSourceDataEXT;
import org.lwjgl.vulkan.VkDescriptorSetAndBindingMappingEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkShaderDescriptorSetAndBindingMappingInfoEXT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S0 验证渲染器:在 -X 400 / -X 2000 块各画一面 16×16 彩色墙(顶点色,无光照),
 * 走官方 26.2 帧图的一个自有 pass,共享主渲染目标的颜色+深度纹理。
 *
 * <p>为什么是 400/2000 而不是 4000(26.2 实测, Options.java:1470-1477):
 * RD 滑块区间 [2,32] → 雾距 end=RD·16 ≤ 512、投影远平面
 * depthFar=max(RD·16·4, cloudRange·16)=2048 @RD32。官方相机物理上
 * 看不到 4000 块 —— 那是 S3 用自有远平面+边界雾要拿回的领地。
 *
 * <p>验收目标(对照 vanilla VVL 零错误基线):
 * <ol>
 *   <li>墙 A(-400, 雾带之前):四色全显,证明自有 pass 经官方管线出图;</li>
 *   <li>近处山丘/地形能遮挡墙 A(共享 reverse-Z 深度);</li>
 *   <li>墙 B(-2000, 官方 depthFar 之内、雾距之外):仍被绘制但 100% 官方雾
 *       (远带行为:几何在官方相机内,颜色被官方 apply_fog 吞没);</li>
 *   <li>VVL(含全开档)全程零报错。</li>
 * </ol>
 *
 * <p>机制完全照抄官方 CloudRenderer 的远物 pass 模板:
 * 默认 uniform 绑定 + writeTransform 动态矩阵 + createRenderPass(主目标颜色/深度)。
 */
public final class FarTerrainRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarTerrainRenderer.class);

    /**
     * 远墙 A 的 X 偏移(块):-X 400 块。
     * 26.2 官方相机上限(实测 Options.java:1474, RD 滑块区间 [2,32]):
     * RD32 下雾距 end=512/start=448、投影远平面 depthFar=max(32·16·4, cloudRange·16)=2048。
     * A 位于雾带之前 → 0% 雾,四色全显,作"出图+深度遮挡"的无歧义对照墙。
     */
    private static final float WALL_A_X = -400.0F;
    /**
     * 远墙 B 的 X 偏移(块):-X 2000 块,位于官方 depthFar(2048)之内、
     * 官方雾距(512)之外 → 100% 官方雾,验证"超远几何仍在官方相机内出图、
     * 被官方雾吞没"的远带行为。4000 块规格(超出官方远平面)留待 S3 自有远平面承接。
     */
    private static final float WALL_B_X = -2000.0F;
    /** 墙面 Y 范围(块):地面带附近,正对地平线方向。 */
    private static final float WALL_Y0 = 56.0F;
    private static final float WALL_Y1 = 72.0F;
    /** 墙面 Z 半宽(块)。 */
    private static final float WALL_HALF_Z = 8.0F;

    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("dhvk", "core/far_terrain");
    private static final Identifier SHADER_PROBE = Identifier.fromNamespaceAndPath("dhvk", "core/far_terrain_probe");
    private static final Identifier SHADER_PROBE2 = Identifier.fromNamespaceAndPath("dhvk", "core/far_terrain_probe2");
    private static final Identifier PIPELINE_LOCATION = Identifier.fromNamespaceAndPath("dhvk", "pipeline/far_terrain");

    /**
     * S1 任务 2 几何堆源 BGL:phantom UBO 绑定 VBO/IBO(描述符 payload = 墙 VBO/IBO 的设备地址区间;
     * 驱动经堆描述符取地址,笔记 §1.6 裁决)。shader 侧对应
     * {@code layout(std140) uniform VBO { vec4 pad; }} 形块;官方 pushDescriptors 对缺失 uniform 抛异常,
     * 故 render() 里必须 setUniform 填充(双通道:classic push 描述符 + 管线创建期静态堆 mapping
     * ——run20 起 mapping 不再是每帧命令链, 见 ensureHeapSurgery)。
     */
    private static final BindGroupLayout GEOMETRY = BindGroupLayout.builder()
        .withUniform("VBO", UniformType.UNIFORM_BUFFER)
        .withUniform("IBO", UniformType.UNIFORM_BUFFER)
        .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(PIPELINE_LOCATION)
        .withVertexShader(SHADER)
        .withFragmentShader(SHADER)
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(GEOMETRY)
        // run27: (ZERO,ONE) 经 run26 双半屏硬件裁决 = descriptor-heap 管线路径下的 dst-pass
        // (源色乘零 → run22-25 全灭根因) → 换 ColorTargetState.DEFAULT = 不启用混合, 纯写入
        // (run26 左半屏红块实证该状态在手术管线下像素级出图; 官方不透明管线同款思路 = 无混合)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();

    /**
     * run25 探针管线: 与 PIPELINE 完全同 BGL(静态映射/手术/捕获全复用), 唯一差异 =
     * 探针 shader(无矩阵裁剪, 自报告堆读值 → 墙面颜色)+ 深度测试恒过不写。
     * 用途: 表字节已被 run24 读回验证全对但墙仍不可见 → 让 GPU 把"每个堆槽实际读到的值"
     * 直接画出来。run26 半退役((ZERO,ONE) 杀死其出图); run27 不绘; run28 以原始值
     * 自报告重新武装 → 裁决出"GPU 经静态映射读 UBO = 零"(恒定 0.5 灰, CPU 真值健康);
     * **run29 哨兵交叉判读**: shader 改为哨兵/真实值三态解码(暗色 = 每帧活体重读 /
     * 亮黄 = 创建期烘焙冻结 / 全黑 = fetch 路径失效), 配合"创建前六槽全填哨兵 +
     * 绘制前每帧重写真实描述符"(见 ensureBuffers/dhvkBindHeap)。
     */
    private static final RenderPipeline PIPELINE_PROBE = RenderPipeline.builder()
        .withLocation(PIPELINE_LOCATION)
        .withVertexShader(SHADER_PROBE)
        .withFragmentShader(SHADER_PROBE)
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(GEOMETRY)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build();

    /**
     * run26 双半屏仲裁管线(判读表见 far_terrain_probe2 shader 头):左半屏纯写入
     * (DEFAULT = 不启用混合 → 纯写),右半屏现用 (ZERO,ONE) 状态。同帧两半互不重叠:
     * (ZERO,ONE) 若 = dst-pass → 右半原样透出场景(青被杀);若 = replace → 右半纯青。
     */
    private static final RenderPipeline PIPELINE_PROBE2_REPLACE = RenderPipeline.builder()
        .withLocation(PIPELINE_LOCATION)
        .withVertexShader(SHADER_PROBE2)
        .withFragmentShader(SHADER_PROBE2)
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(GEOMETRY)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build();

    private static final RenderPipeline PIPELINE_PROBE2_BLEND = RenderPipeline.builder()
        .withLocation(PIPELINE_LOCATION)
        .withVertexShader(SHADER_PROBE2)
        .withFragmentShader(SHADER_PROBE2)
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(GEOMETRY)
        // run27: 与主管线同一状态(纯写入) —— probe2 退役为备用仲裁器, 状态不再分叉
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build();

    /** run32 Ace2: DHVK 四条堆管线名册(ensureHeapSurgery 登记进 push 取消名册,
     *  见 VulkanRenderPassPushCancelMixin). */
    private static final RenderPipeline[] DHVK_PIPELINES = {PIPELINE, PIPELINE_PROBE, PIPELINE_PROBE2_REPLACE,
        PIPELINE_PROBE2_BLEND};

    /** S1 任务 2: 几何描述符承载堆(持久,init 建一次;墙 A/B 的 VBO/IBO 地址注册在 cell 0)。 */
    private static final long CELL_CAPACITY = 8192L;

    // run20 静态映射字面量(LWJGL 3.4.1 新生代结构体 STYPE 半初始化 = 0, run6 教训 → 字面量;
    // 侦察报告 Q4 javap 双验)。
    private static final int STYPE_SET_BINDING_MAPPING = 1000135005;
    private static final int STYPE_SHADER_MAPPING_INFO = 1000135006;
    private static final int RESOURCE_MASK_UNIFORM_BUFFER = 32; // VK14 VK_SPIRV_RESOURCE_TYPE_UNIFORM_BUFFER_BIT_EXT

    /**
     * 全量静态映射(11312 硬规约: 全部 set/binding 变量必须有映射): 合并 layout 的
     * 绑定索引 → uniform 名。**run23 实测序**(run22 日志 layout 扫描): VBO=0 IBO=1
     * Projection=2 DynamicTransforms=3 Fog=4; 第 6 位(索引 5)= Globals —— shader 接口
     * 不引用它(VVL run21 只点名 b3/b4), 后端可能裁掉该条目(run22 实测扫描 null),
     * 第 6 条映射 VVL 零点名(实测合法)→ 保留为死槽。
     * 绑定 i 的堆槽位 = (i/2, i%2)(每 cell 2 槽, descSize 16B)。
     */
    private static final String[] EXPECTED_BINDINGS =
        {"VBO", "IBO", "Projection", "DynamicTransforms", "Fog", "Globals"};

    /** 官方 uniform ring 缓冲的 BDA 缓存(ring 缓冲持久, 设备地址恒定; 2 代查询一次,
     *  run17 教训: VVL 1.4.341 的 1 代 walker 有内部悬空指针 bug)。render 线程独占。 */
    private static final java.util.Map<Long, Long> BDA_CACHE = new java.util.HashMap<>();

    /** run24: 前 N 帧做"期望 vs 主机读回"表验证(之后静默)。render 线程独占。 */
    private static final int TABLE_CHECK_FRAMES = 5;
    private static int frameCounter;
    /** run30: 设备 minUniformBufferOffsetAlignment(run30LogDeviceFacts 首帧填充,
     *  未查询前 = 0 跳过对齐审计)。UBO 描述符地址必须满足该对齐(VUID-12350),
     *  5090 驱动是否硬执行待 run30 硬件裁决。 */
    private static long uboAlign = 0L;

    private static GpuBuffer vertexBuffer;
    private static GpuBufferSlice vertexSlice;
    private static GpuBuffer indexBuffer;
    /** run29 哨兵缓冲:6 个 64B 槽, 槽 i 的 UBO 行0.x = i+1(余 0)。管线创建前把六槽
     * 描述符全填成哨兵 → 创建时刻堆表全非零且每槽可识别; 绘制前每帧重写为真实
     * 描述符。绘制时刻 GPU 读到哨兵值 = 创建期烘焙(冻结), 读到真实值 = 每帧活体,
     * 读到零 = fetch 路径失效。 */
    private static GpuBuffer sentinelBuffer;
    private static long sentinelBase = 0L;
    /** run26 双半屏探针:左半(墙 A 侧顶点)/右半(墙 B 侧顶点)各一对半墙缓冲。 */
    private static GpuBuffer vertexBufferL;
    private static GpuBufferSlice vertexSliceL;
    private static GpuBuffer indexBufferL;
    private static GpuBuffer vertexBufferR;
    private static GpuBufferSlice vertexSliceR;
    private static GpuBuffer indexBufferR;
    private static DescriptorHeap heap;
    /** 墙 VBO/IBO 的设备地址(注册进堆描述符的 payload)。 */
    private static long vboDeviceAddress = 0L;
    private static long iboDeviceAddress = 0L;

    private FarTerrainRenderer() {
    }

    /**
     * 设备关闭前释放自有缓冲:VVL 对象追踪要求 vkDestroyDevice 之前释放全部
     * 设备子对象,否则退出时报 "VkBuffer ... has not been destroyed"。
     * 由 RenderSystemShutdownMixin 在 RenderSystem.shutdownRenderer HEAD 调用
     * (DEVICE.close 之前),幂等。
     */
    public static synchronized void dispose() {
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
            vertexSlice = null;
        }
        if (indexBuffer != null) {
            indexBuffer.close();
            indexBuffer = null;
        }
        if (sentinelBuffer != null) {
            sentinelBuffer.close();
            sentinelBuffer = null;
            sentinelBase = 0L;
        }
        if (vertexBufferL != null) {
            vertexBufferL.close();
            vertexBufferL = null;
            vertexSliceL = null;
        }
        if (indexBufferL != null) {
            indexBufferL.close();
            indexBufferL = null;
        }
        if (vertexBufferR != null) {
            vertexBufferR.close();
            vertexBufferR = null;
            vertexSliceR = null;
        }
        if (indexBufferR != null) {
            indexBufferR.close();
            indexBufferR = null;
        }
        // S1 任务 2: 堆表只引用墙缓冲的设备地址(数字, 无对象依赖) → arena 之后、设备关闭之前关堆
        if (heap != null) {
            heap.close();
            heap = null;
        }
        vboDeviceAddress = 0L;
        iboDeviceAddress = 0L;
        // run20: 手术态复位(永生节点内存不回收 —— 管线 destroy 在设备 close 时, 晚于本方法,
        // VVL 的 pNext walker 仍可能摸链)
        DhVkClient.mappingInfoNode = 0L;
        DhVkClient.pipelineSurgeryArmed = false;
        DhVkClient.DHVK_PIPELINES.clear();
        frameCounter = 0;
    }

    /** 把远几何 pass 挂进官方帧图:与云 pass 同款,读写主目标(共享深度)。mod 自禁用时不挂 pass。 */
    public static void attach(final FrameGraphBuilder frame, final LevelTargetBundle targets) {
        if (DhVkClient.disabled) {
            return;
        }
        FramePass pass = frame.addPass("far_terrain");
        targets.main = pass.readsAndWrites(targets.main);
        pass.executes(FarTerrainRenderer::render);
    }

    /** run30 对齐/事实采集帧。run29 哨兵全黑 = GPU 读到的描述符是零 —— 既非活体重读
     *  (应暗色)也非创建期烘焙(应亮黄): 嫌疑收窄为 ① UBO 描述符地址不满足
     *  minUniformBufferOffsetAlignment 被 5090 拒读(VUID-12350; 官方 ring/Proj 缓冲
     *  BDA 实测 ≡ 192/128 mod 256, 若 5090 对齐 = 256 则整族命中) ② 偏移基准/
     *  payload 位模式被驱动按别的解释 ③ fetch 路径整体死。
     *  本帧: 探针换无歧义长度自报告(品红 = 全健康 / 纯蓝 = 仅自有对齐缓冲健康
     *  / 全黑 = 整体失效) + 首帧 log 设备对齐 limits + 六槽描述符地址逐一对齐审计。
     *  RD 判读不变: 视频设置拉满 32, 墙 A 才回到雾带前。 */
    public static void render() {
        if (DhVkClient.disabled) {
            return;
        }
        ensureBuffers();
        // 首帧竞态: 本帧帧图在 gate 判定前已挂 pass, ensureBuffers 内 gate 失败必须当场退出
        // (否则空 buffer 走 drawIndexed, GL 回退后端 NPE / VVL 报错)
        if (DhVkClient.disabled) {
            return;
        }
        frameCounter++;
        run30LogDeviceFacts();
        // run20: 强制 PIPELINE 的首次编译发生在手术窗口内(静态堆 mapping + flags2 描述符堆位,
        // 官方 pipelineCache 缓存首次结果, 后续每帧 setPipeline 全部命中手术过的管线)
        ensureHeapSurgery();
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);
        // run28 CPU 真值: 本帧写入 DT ring 的 ModelView 三行(相机基向量, 健康 ∈ [-1,1] 分量)。
        // 与探针 GPU 解码(RGB = 0.5+行0分量)对拍: 一致 = 静态映射抓取健康, 灰 = 读零。
        if (frameCounter <= TABLE_CHECK_FRAMES) {
            LOGGER.info("[dhvk] run28 CPU truth frame={}: mv row0=({},{},{}) row1=({},{},{}) row2=({},{},{})",
                    frameCounter,
                    modelView.m00(), modelView.m01(), modelView.m02(),
                    modelView.m10(), modelView.m11(), modelView.m12(),
                    modelView.m20(), modelView.m21(), modelView.m22());
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();

        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        // S1 任务 2: wrapper 即堆绑定的发射点(笔记 §2); CBU 经 DhvkCommandEncoder
        // 探针接口读取(官方 wrapper 的 backend 访问器是 protected, mod 侧不可见)
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // run22 捕获窗: pass 建立后张开, dhvkBindHeap 之后关闭(render 线程单线程, 无竞态);
        // 窗内 setUniform 由 RenderPassUniformProbeMixin 记入 DhVkClient.uniformSlices
        DhVkClient.uniformCaptureArmed = true;
        DhVkClient.UNIFORM_SLICES.clear();
        try (RenderPass renderPass = encoder.createRenderPass(
                () -> "FarTerrain", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            // 主管线(DEFAULT 纯写入)绘双墙; 几何经堆源描述符(cell 0), uniform 经
            // 每帧堆表重写(表字节 run24 已读回验证 6/6 一致)。
            renderPass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            // phantom 绑定填充:官方 pushDescriptors 对合并 layout 的每个 UNIFORM_BUFFER 条目
            // 缺值即抛 "Missing uniform", 且恒写 classic 描述符 —— run22 起全量堆映射在其上重定向
            renderPass.setUniform("VBO", vertexSlice);
            renderPass.setUniform("IBO", indexBuffer.slice());
            renderPass.setVertexBuffer(0, vertexSlice);
            renderPass.setIndexBuffer(indexBuffer, IndexType.SHORT);
            // 堆源绑定:每帧重写 official uniform 堆描述符 + 绑整表(setPipeline 后、drawIndexed 前)
            dhvkBindHeap(encoder);
            renderPass.drawIndexed(12, 1, 0, 0, 0);
            // run28: 探针二绘(顶点/索引绑定沿主管线, 堆状态沿用; 探针 layout 同名 uniform
            // 已由主管线 setUniform 记录 → 其 pushDescriptors 推同款 classic 描述符)。
            // NDC 钉死 + 深度恒过 → 无论矩阵健康与否, 两块色板必现屏; RGB = ModelViewMat
            // 行0 原始值(GPU 侧堆读真值), 与 CPU truth 日志对拍裁决 UBO 静态映射抓取。
            renderPass.setPipeline(PIPELINE_PROBE);
            renderPass.drawIndexed(12, 1, 0, 0, 0);
        } finally {
            DhVkClient.uniformCaptureArmed = false;
        }
    }

    /** run22: 每帧在 encoder 当前 CBU 上: ① 对 6 个绑定(VBO/IBO + Projection/
     *  DynamicTransforms/Fog/Globals, run23 实测序)重写堆描述符 —— VBO/IBO 槽 run29 起
     *  亦每帧重写(创建前哨兵占位, 绘制时刻表内必须是真实墙缓冲描述符); 官方 uniform 槽
     *  payload = 本帧官方 ring 缓冲 slice 的设备地址区间(ring offset 每帧滚动,
     *  缓冲设备地址经 BDA_CACHE 恒定); ② 绑整表(2026: mapping 已在管线创建期静态声明,
     *  bind 只带堆范围 + 驱动预留区, pNext 恒 NULL)。
     *  run23 换位裁决(run22 根因): 2 号绑定实测 = Projection(非 Globals)——shader 的 ProjMat
     *  读 2 号槽, run22 把 Globals slice 塞进去 → gl_Position 被裁没, 双墙全灭。 */
    private static void dhvkBindHeap(final CommandEncoder encoder) {
        if (heap == null) {
            return;
        }
        long cbu = ((DhvkCommandEncoder) (Object) encoder).dhvkCurrentCbu();
        if (cbu == 0L) {
            return;
        }
        // run34 哨兵横扫: 六槽全指哨兵 buffer 六分片(offset 64*i, 头 float = i+1;
        // 矩阵分片近单位阵 / 雾分片 end=大值, 主墙变换保持正常), 全 host-A 裸 [addr,size]
        // (尺寸沿用官方记录宽度, VVL range 规则干净); 探针六通道各读一片头 /6 →
        // 一次 run 画完表槽活死地图; 若全黑, 次炉 34b 把映射+表写整体搬入保留区再裁。
        if (sentinelBase == 0L) {
            return;
        }
        // run37 真实数据收口: 六槽每帧 = 官方 ring 本帧 slice / 墙 VBO/IBO, 全 API 方言
        // (run35-36 定谳: 驱动序列化字节 = 5090 堆槽唯一可解码格式; 偏移 0 亦活)。
        writeUniformSlot(0, "VBO", true);
        writeUniformSlot(1, "IBO", true);
        long[] proj = writeUniformSlot(2, "Projection", true);
        long[] dt = writeUniformSlot(3, "DynamicTransforms", true);
        long[] fog = writeUniformSlot(4, "Fog", true);
        long[] glob = writeUniformSlot(5, "Globals", true);
        if (frameCounter <= TABLE_CHECK_FRAMES) {
            logTableCheck(proj, dt, fog, glob);
        }
        heap.bind(cbu, 0L, heap.sizeBytes());
    }

    /** 绑定 i(槽位 (i/2, i%2)) 的堆描述符 = 本帧该 uniform 的官方 ring slice 地址区间。
     *  数据本身由官方后端在同队列上传, GPU 读取天然有序(同 CBU 先后, 无同步兜底)。
     *  run24: 返回写入的 (deviceAddress, rangeSize) 供读回验证; slice 缺失返回 null。
     *  run31: viaApi 决定表槽字节来源(驱动不透明字节 vs host 手写), 见 dhvkBindHeap。 */
    private static long[] writeUniformSlot(int binding, String name, boolean viaApi) {
        GpuBufferSlice slice = DhVkClient.UNIFORM_SLICES.get(name);
        if (slice == null) {
            LOGGER.warn("[dhvk] run22 uniform slice missing for {} this frame (capture window?) "
                    + "-> slot keeps previous frame's descriptor", name);
            return null;
        }
        long vkBuffer = ((VulkanGpuBuffer) slice.buffer()).vkBuffer();
        Long base = BDA_CACHE.get(vkBuffer);
        if (base == null) {
            base = bdaAddress2(vkBuffer);
            BDA_CACHE.put(vkBuffer, base);
        }
        long addr = base + slice.offset();
        long size = slice.length();
        heap.writeBufferDescriptor(binding / 2, binding % 2, addr, size, viaApi);
        return new long[] {addr, size};
    }

    /** run30 设备事实采集(每 run 一次): minUniformBufferOffsetAlignment 等对齐 limits
     *  —— VUID-12350 要求 UBO 描述符地址为 minUniformBufferOffsetAlignment 的倍数,
     *  官方 ring/Proj 缓冲 BDA 实测 ≡ 192/128 mod 256; 若 5090 该 limit = 256,
     *  静态映射 UBO 读零(探针全黑/灰)可直接归因。 */
    private static void run30LogDeviceFacts() {
        if (uboAlign != 0L || VkHandles.pdevWrapper == null) {
            return;
        }
        // LWJGL 3.4.1 快照: 无独立 vkGetPhysicalDeviceLimits 绑定, limits 经
        // VkPhysicalDeviceProperties.pLimits 子结构读取(永生小分配, 同 mapping 节点模式)
        long propsAddr = MemoryUtil.nmemCalloc(VkPhysicalDeviceProperties.SIZEOF, 16);
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.create(propsAddr);
        VK10.vkGetPhysicalDeviceProperties(VkHandles.pdevWrapper, props);
        VkPhysicalDeviceLimits limits = props.limits();
        uboAlign = limits.minUniformBufferOffsetAlignment();
        LOGGER.info("[dhvk] run30 device facts: minUniformBufferOffsetAlignment={}B "
                        + "minStorageBufferOffsetAlignment={}B maxUniformBufferRange={}B",
                limits.minUniformBufferOffsetAlignment(),
                limits.minStorageBufferOffsetAlignment(), limits.maxUniformBufferRange());
        // run31: UBO 描述符不透明字节宽(驱动方言) —— VUID-11207 要求写目标槽宽 ≥ 此值,
        // 同时回答"驱动写多少字节进表槽 / GPU 从表槽读多少字节"。
        long uboDescSize = EXTDescriptorHeap.vkGetPhysicalDeviceDescriptorSizeEXT(
                VkHandles.pdevWrapper, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        LOGGER.info("[dhvk] run31 device facts: descriptorSize(UNIFORM_BUFFER)={}B "
                        + "(表槽宽={}B, 驱动仅写/读前 N 字节, 槽宽 ≥ N 即合规)",
                uboDescSize, heap.slotStrideBytes());
    }

    /** run24 读回验证: 六槽"期望 (addr,size)" vs 主机映射实际字节(写路径 A 是 coherent →
     *  主机读回 = 设备视图)+ 官方 slice 元数据; 首帧另倒表头 128B 原始 hex
     *  + run30 六槽描述符地址对齐审计(addr % minUniformBufferOffsetAlignment)。
     *  目的: 若墙仍不可见, 日志直接给出哪一槽哪个字节不对, 不再靠推断。 */
    private static void logTableCheck(long[] proj, long[] dt, long[] fog, long[] glob) {
        long base = heap.hostAddress();
        if (base == 0L) {
            LOGGER.warn("[dhvk] run24 table check: heap not host-visible, readback skipped");
            return;
        }
        LOGGER.info("[dhvk] run24 table check frame={} (slotStride={}B, run37 真实数据: 六槽 = 官方 ring "
                + "本帧 slice / 墙 VBO/IBO, 全 API 方言指纹(免严格比对)):",
                frameCounter, heap.slotStrideBytes());
        checkSlot(0, 0, "VBO", vboDeviceAddress, vertexBuffer.size(), base, true);
        checkSlot(0, 1, "IBO", iboDeviceAddress, indexBuffer.size(), base, true);
        checkSlot(1, 0, "Projection", proj == null ? -1L : proj[0], proj == null ? -1L : proj[1], base, true);
        checkSlot(1, 1, "DynamicTransforms", dt == null ? -1L : dt[0], dt == null ? -1L : dt[1], base, true);
        checkSlot(2, 0, "Fog", fog == null ? -1L : fog[0], fog == null ? -1L : fog[1], base, true);
        checkSlot(2, 1, "Globals", glob == null ? -1L : glob[0], glob == null ? -1L : glob[1], base, true);
        for (String n : new String[] {"Projection", "DynamicTransforms", "Fog", "Globals"}) {
            GpuBufferSlice s = DhVkClient.UNIFORM_SLICES.get(n);
            if (s == null) {
                continue;
            }
            long vb = ((VulkanGpuBuffer) s.buffer()).vkBuffer();
            Long bda = BDA_CACHE.get(vb);
            LOGGER.info("[dhvk] run24 slice meta: {} buf=0x{} bda=0x{} off={}B len={}B",
                    n, Long.toHexString(vb), bda == null ? "nq" : Long.toHexString(bda),
                    s.offset(), s.length());
        }
        if (frameCounter == 1) {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 128; i++) {
                hex.append(String.format("%02x", MemoryUtil.memGetByte(base + i) & 0xFF));
            }
            LOGGER.info("[dhvk] run24 table raw @host (first 128B): {}", hex);
            // run30 对齐审计: 六槽描述符地址 % minUniformBufferOffsetAlignment(0 = 对齐,
            // 非 0 = VUID-12350 违例, 5090 是否据此拒读 UBO 由探针色板硬件裁决)
            if (uboAlign > 0L) {
                long a = proj == null ? -1L : proj[0];
                long b = dt == null ? -1L : dt[0];
                long c = fog == null ? -1L : fog[0];
                long d = glob == null ? -1L : glob[0];
                LOGGER.info("[dhvk] run30 align audit (mod {}B): VBO=0x{}({}) IBO=0x{}({}) "
                                + "Proj=0x{}({}) DT=0x{}({}) Fog=0x{}({}) Glob=0x{}({})",
                        uboAlign,
                        Long.toHexString(vboDeviceAddress), vboDeviceAddress % uboAlign,
                        Long.toHexString(iboDeviceAddress), iboDeviceAddress % uboAlign,
                        Long.toHexString(a), a % uboAlign,
                        Long.toHexString(b), b % uboAlign,
                        Long.toHexString(c), c % uboAlign,
                        Long.toHexString(d), d % uboAlign);
            }
        }
    }

    /** 单槽读回比对: 期望 (addr,size) vs 主机实际, MISMATCH 即红灯(期望缺失 = -1 占位)。
     *  run31: viaApi=true 的槽由驱动写不透明字节(实现方言), 不做逐字节严格比对,
     *  只倒原始 hex 作跨 run 指纹(规格保证: 相同输入 → 相同位模式)。 */
    private static void checkSlot(int cell, int slot, String name, long expAddr, long expSize,
            long base, boolean viaApi) {
        long off = heap.slotOffsetOf(cell, slot);
        long gotAddr = MemoryUtil.memGetLong(base + off);
        long gotSize = MemoryUtil.memGetLong(base + off + 8L);
        if (viaApi) {
            LOGGER.info("[dhvk] run24 slot({},{}) {} off={}B: host=[0x{}, 0x{}] expect=[0x{}, 0x{}] "
                    + "(API 不透明字节, 免严格比对)",
                    cell, slot, name, off, Long.toHexString(gotAddr), Long.toHexString(gotSize),
                    Long.toHexString(expAddr), Long.toHexString(expSize));
            return;
        }
        boolean ok = gotAddr == expAddr && gotSize == expSize;
        LOGGER.info("[dhvk] run24 slot({},{}) {} off={}B: host=[0x{}, 0x{}] expect=[0x{}, 0x{}] {}",
                cell, slot, name, off, Long.toHexString(gotAddr), Long.toHexString(gotSize),
                Long.toHexString(expAddr), Long.toHexString(expSize), ok ? "OK" : "MISMATCH");
    }

    /**
     * run20/22 一次性管线手术(2026 静态映射收口, 设计定稿见笔记 run20/run22 节):
     * <ol>
     *   <li>建**全量** set0 绑定(EXPECTED_BINDINGS 六名)→堆源映射数组 + info 节点 ——
     *       全 native heap 永生(VVL 的 walker 会摸这些裸指针; 管线销毁晚于我们的 dispose,
     *       不回收); run22: 11312 硬规约要求全部 set/binding 变量有映射;</li>
     *   <li>置 {@link DhVkClient#mappingInfoNode} + 上膛手术窗 →
     *       {@code precompilePipeline(PIPELINE)} 强制本管线**首次编译**发生在手术下
     *       (VulkanRenderPipelineSurgeryMixin 把 flags2 描述符堆位 + null layout + 静态映射
     *       注入官方 compile(); 官方 pipelineCache 缓存该结果, 后续 setPipeline 全部命中);</li>
     *   <li>卸膛后全表 log + 按名扫描: 前 5 名(实测 VBO=0 IBO=1 Projection=2 DynamicTransforms=3
     *       Fog=4)必须与合并 layout 实测相符, 任一不符打 ERROR(红灯判据); 第 6 名(Globals)
     *       软验(run22 实测后端裁掉该条目)。</li>
     * </ol>
     */
    private static synchronized void ensureHeapSurgery() {
        if (DhVkClient.mappingInfoNode != 0L || heap == null) {
            return;
        }
        // 映射数组(6 条, 连续, native heap 永生); 3.4.1 代码生成怪癖: info 节点无
        // mappingCount fluent setter、$Buffer.create 是 6 参内部工厂 → n- 静态 setter +
        // $Buffer 公共构造器(包裹裸地址, 无所有权不回收)
        long arr = MemoryUtil.nmemCalloc(
                (long) EXPECTED_BINDINGS.length * VkDescriptorSetAndBindingMappingEXT.SIZEOF, 16);
        for (int i = 0; i < EXPECTED_BINDINGS.length; i++) {
            writeHeapMapping(arr + (long) i * VkDescriptorSetAndBindingMappingEXT.SIZEOF, i,
                    heap.slotOffsetOf(i / 2, i % 2));
        }
        long infoNode = MemoryUtil.nmemCalloc(32, 16);
        VkShaderDescriptorSetAndBindingMappingInfoEXT.nsType(infoNode, STYPE_SHADER_MAPPING_INFO);
        VkShaderDescriptorSetAndBindingMappingInfoEXT.npNext(infoNode, 0L);
        VkShaderDescriptorSetAndBindingMappingInfoEXT.nmappingCount(infoNode, EXPECTED_BINDINGS.length);
        VkShaderDescriptorSetAndBindingMappingInfoEXT.npMappings(infoNode,
                new VkDescriptorSetAndBindingMappingEXT.Buffer(arr, EXPECTED_BINDINGS.length));
        DhVkClient.mappingInfoNode = infoNode;
        DhVkClient.pipelineSurgeryArmed = true;
        try {
            RenderSystem.getDevice().precompilePipeline(PIPELINE);
            RenderSystem.getDevice().precompilePipeline(PIPELINE_PROBE);
            RenderSystem.getDevice().precompilePipeline(PIPELINE_PROBE2_REPLACE);
            RenderSystem.getDevice().precompilePipeline(PIPELINE_PROBE2_BLEND);
        } finally {
            DhVkClient.pipelineSurgeryArmed = false;
        }
        // run32 Ace2: 四条堆管线登记进官方 push 取消名册 —— precompile/getOrCompilePipeline
        // 共享 pipelineCache(computeIfAbsent, 按 RenderPipeline 身份缓存) → 名册实例即官方
        // setPipeline 将取回的 record, 身份比对成立; 此后名册管线的 draw 触发官方
        // pushDescriptors 时被 HEAD 取消(VulkanRenderPassPushCancelMixin), 堆状态不再
        // 被经典 push 互斥失效(run27-31 全黑的头号嫌疑, 见笔记 run32 设计修订)
        for (RenderPipeline rp : DHVK_PIPELINES) {
            if (RenderSystem.getDevice().precompilePipeline(rp) instanceof VulkanRenderPipeline vrp) {
                DhVkClient.registerDhvkPipeline(vrp);
            }
        }
        LOGGER.info("[dhvk] run32 push-cancel roster armed: {} heap pipelines "
                + "(classic pushDescriptors HEAD-cancelled on their draws)",
                DhVkClient.dhvkPipelineCount());
        // 按名扫描(缓存命中, 不重编译): 静态映射声明的 firstBinding 序必须与合并 layout 实测一致
        java.util.Map<String, Integer> scanned = scanLayoutBindingNames();
        // run23: 全表 log —— run22 的 Globals=null 之谜 → 直接把 layout 全部条目倒出来, 不再靠推断
        java.util.List<java.util.Map.Entry<String, Integer>> all =
                new java.util.ArrayList<>(scanned.entrySet());
        all.sort(java.util.Map.Entry.comparingByValue());
        StringBuilder fullTable = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : all) {
            fullTable.append(e.getValue()).append('=').append(e.getKey()).append(' ');
        }
        LOGGER.info("[dhvk] run23 layout binding table: [{}]", fullTable.toString().trim());
        StringBuilder table = new StringBuilder();
        boolean mismatch = false;
        for (int i = 0; i < EXPECTED_BINDINGS.length; i++) {
            Integer got = scanned.get(EXPECTED_BINDINGS[i]);
            table.append(EXPECTED_BINDINGS[i]).append('=').append(got).append(' ');
            // 前 5 名 = shader 接口实际引用的绑定, 硬验红灯; 第 6 名(Globals)后端可能裁条目
            // (run22 实测 null)→ 软验, 只进 table 不判红
            if (i < EXPECTED_BINDINGS.length - 1 && (got == null || got != i)) {
                mismatch = true;
            }
        }
        if (mismatch) {
            LOGGER.error("[dhvk] run23 mapping-layout mismatch: declared order [{}] but layout "
                    + "scan gives [{}] -> wall geometry/uniforms will be misbound, arbitrate via "
                    + "VVL/visuals", String.join(" ", EXPECTED_BINDINGS), table.toString().trim());
        } else {
            LOGGER.info("[dhvk] run23 full static mapping verified: [{}]", table.toString().trim());
        }
        LOGGER.info("[dhvk] run22 heap surgery applied: {} static mappings (set0) + null layout + "
                + "pipeline flags2 descriptor-heap bit; first compile ran under surgery",
                EXPECTED_BINDINGS.length);
    }

    /** 按名扫描合并 set layout 的条目 → 名 → 绑定索引(管线未编译/失败时空表)。 */
    private static java.util.Map<String, Integer> scanLayoutBindingNames() {
        java.util.Map<String, Integer> names = new java.util.HashMap<>();
        if (RenderSystem.getDevice().precompilePipeline(PIPELINE) instanceof VulkanRenderPipeline vrp) {
            List<com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.Entry> entries = vrp.layout().entries();
            for (int i = 0; i < entries.size(); i++) {
                names.putIfAbsent(entries.get(i).name(), i);
            }
        }
        return names;
    }

    /** 写一条静态映射(native heap 节点; sourceData 是内联合体, 经栈上结构体按字节拷入)。 */
    private static void writeHeapMapping(long nodeAddr, int firstBinding, long heapSlotOffset) {
        VkDescriptorSetAndBindingMappingEXT.nsType(nodeAddr, STYPE_SET_BINDING_MAPPING);
        VkDescriptorSetAndBindingMappingEXT.npNext(nodeAddr, 0L);
        VkDescriptorSetAndBindingMappingEXT.ndescriptorSet(nodeAddr, 0); // push 描述符集 = set 0
        VkDescriptorSetAndBindingMappingEXT.nfirstBinding(nodeAddr, firstBinding);
        VkDescriptorSetAndBindingMappingEXT.nbindingCount(nodeAddr, 1);
        VkDescriptorSetAndBindingMappingEXT.nresourceMask(nodeAddr, RESOURCE_MASK_UNIFORM_BUFFER);
        VkDescriptorSetAndBindingMappingEXT.nsource(nodeAddr, 0); // HEAP_WITH_CONSTANT_OFFSET_EXT
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorMappingSourceConstantOffsetEXT co = VkDescriptorMappingSourceConstantOffsetEXT.calloc(stack)
                    .heapOffset((int) heapSlotOffset)
                    .heapArrayStride(0);
            VkDescriptorMappingSourceDataEXT sourceData = VkDescriptorMappingSourceDataEXT.calloc(stack)
                    .constantOffset(co);
            VkDescriptorSetAndBindingMappingEXT.nsourceData(nodeAddr, sourceData);
        }
    }

    private static synchronized void ensureBuffers() {
        if (vertexBuffer != null) {
            return;
        }
        // 首帧设备已就绪:硬门槛检查(幂等)——descriptorHeap feature 未置位则 mod 自禁用
        DhVkClient.checkDeviceGate();
        if (DhVkClient.disabled) {
            LOGGER.info("[dhvk] far-terrain buffers skipped: mod disabled by device gate");
            return;
        }

        // 8 个四色角点(墙 A ×4 + 墙 B ×4):vec3 位置(块,float32)+ vec4 颜色(RGBA8_UNORM)。
        ByteBuffer vbo = ByteBuffer.allocateDirect(8 * (3 * 4 + 4)).order(ByteOrder.LITTLE_ENDIAN);
        putQuad(vbo, WALL_A_X);
        putQuad(vbo, WALL_B_X);
        vbo.rewind();

        ByteBuffer ibo = ByteBuffer.allocateDirect(12 * 2).order(ByteOrder.LITTLE_ENDIAN);
        putQuadIndices(ibo, 0);
        putQuadIndices(ibo, 4);
        ibo.rewind();

        // S1 任务 2: usage 叠加 USAGE_UNIFORM —— 官方 pushDescriptors 对 UNIFORM_BUFFER 条目
        // 强制 usage & 128, phantom 绑定要经 setUniform 用这两个缓冲填充(笔记 §8);
        // VulkanConst.bufferUsageToVk 逐位 OR, 组合位合法.
        // run13 根因 ②: 堆描述符 payload = 本缓冲的设备地址 → vkGetBufferDeviceAddress 要求
        // usage 带设备地址位(2026 值空间 = SHADER_DEVICE_ADDRESS, 经 VulkanConstBdaUsageMixin
        // 从 DEVICE_ADDRESS 标记位折算) + DEVICE_ADDRESS 内存(VMA 自动), 三要素齐备方合法
        vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_vbo",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS,
                vbo);
        vertexSlice = vertexBuffer.slice();
        indexBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_ibuffer",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS,
                ibo);
        LOGGER.info("[dhvk] S0 far-terrain buffers created (vbo={}B, ibo={}B)", vbo.capacity(), ibo.capacity());

        // run26 双半屏仲裁:左半=墙 A 侧四顶点(纯色, probe2 VS 按 (y,z) 符号钉左半屏),
        // 右半=墙 B 侧四顶点(钉右半屏); 各 6 条索引 = 半屏一个 quad
        ByteBuffer vboL = ByteBuffer.allocateDirect(4 * (3 * 4 + 4)).order(ByteOrder.LITTLE_ENDIAN);
        putQuad(vboL, WALL_A_X);
        vboL.rewind();
        ByteBuffer iboL = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.LITTLE_ENDIAN);
        putQuadIndices(iboL, 0);
        iboL.rewind();
        ByteBuffer vboR = ByteBuffer.allocateDirect(4 * (3 * 4 + 4)).order(ByteOrder.LITTLE_ENDIAN);
        putQuad(vboR, WALL_B_X);
        vboR.rewind();
        ByteBuffer iboR = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.LITTLE_ENDIAN);
        putQuadIndices(iboR, 0);
        iboR.rewind();

        vertexBufferL = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_probe2_vboL",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS, vboL);
        vertexSliceL = vertexBufferL.slice();
        indexBufferL = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_probe2_ibufferL",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS, iboL);
        vertexBufferR = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_probe2_vboR",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS, vboR);
        vertexSliceR = vertexBufferR.slice();
        indexBufferR = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_probe2_ibufferR",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS, iboR);
        LOGGER.info("[dhvk] run26 probe2 buffers created (halves: vbo=64B x2, ibo=12B x2)");

        // S1 任务 2: 描述符堆 = 墙几何地址的承载体(持久堆; 任务 3 才换正式 arena,
        // 本任务复用 S0 GpuBuffer 当 arena 雏形, 只读其设备地址)
        if (VkHandles.deviceWrapper == null) {
            throw new IllegalStateException("[dhvk] device wrapper not captured before heap init");
        }
        heap = new DescriptorHeap(CELL_CAPACITY, 2);
        // run17 规避(VVL 1.4.341 的 1 代 vkGetBufferDeviceAddress pNext walker 内部悬空指针 SEGV):
        // vbo/ibo 被 VMA 先 bind 躲不开 → 走 2 代 vkGetBufferDeviceAddress2(VVL 独立验证函数,
        // pInfo 空链, 1 代 walker 够不着)
        vboDeviceAddress = bdaAddress2(((VulkanGpuBuffer) vertexBuffer).vkBuffer());
        iboDeviceAddress = bdaAddress2(((VulkanGpuBuffer) indexBuffer).vkBuffer());
        heap.writeBufferDescriptor(0, 0, vboDeviceAddress, vertexBuffer.size());
        heap.writeBufferDescriptor(0, 1, iboDeviceAddress, indexBuffer.size());
        LOGGER.info("[dhvk] wall geometry registered in descriptor heap (cell 0: "
                + "vbo@0x{} len={}B, ibo@0x{} len={}B)",
                vboDeviceAddress, vertexBuffer.size(), iboDeviceAddress, indexBuffer.size());

        // run29 哨兵: 管线创建前把六槽**全部**填成哨兵描述符(槽 i 行0.x = i+1)——
        // 创建时刻堆表全非零、每槽可识别。ensureHeapSurgery 在本方法之后运行,
        // 哨兵先于任何管线编译落表; 之后每帧 dhvkBindHeap 将六槽重写为真实描述符。
        ByteBuffer sentinel = ByteBuffer.allocateDirect(6 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 6; i++) {
            float head = (float) (i + 1);
            int from;
            if (i == 2 || i == 3) {
                // run34: 近单位阵(row0.x = head)—— 主墙在哨兵矩阵下变换仍正常
                sentinel.putFloat(head).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
                sentinel.putFloat(0.0F).putFloat(1.0F).putFloat(0.0F).putFloat(0.0F);
                sentinel.putFloat(0.0F).putFloat(0.0F).putFloat(1.0F).putFloat(0.0F);
                sentinel.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).putFloat(1.0F);
                from = 16;
            } else if (i == 4) {
                // run34: fogStart = head, fogEnd 大值 —— 主墙不被哨兵雾染白
                sentinel.putFloat(head);
                sentinel.putFloat(65536.0F);
                from = 2;
            } else {
                sentinel.putFloat(head);
                from = 1;
            }
            for (int j = from; j < 16; j++) {
                sentinel.putFloat(0.0F);
            }
        }
        sentinel.rewind();
        sentinelBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_sentinel",
                GpuBuffer.USAGE_UNIFORM | DeviceAddressUsage.DEVICE_ADDRESS, sentinel);
        sentinelBase = bdaAddress2(((VulkanGpuBuffer) sentinelBuffer).vkBuffer());
        for (int i = 0; i < 6; i++) {
            heap.writeBufferDescriptor(i / 2, i % 2, sentinelBase + (long) i * 64, 64L);
        }
        // run35 先登记后手写: 哨兵 buffer 一次性经 vkWriteResourceDescriptorsEXT 登记进
        // 驱动内部 BDA 表(scratch 槽 (4,0), 静态映射不引用 → 永不被取数), 之后表槽里的
        // 手写裸字节才有'认识的地址'可解码(run34 全黑头号嫌疑 = 未登记地址按零解码)。
        heap.writeBufferDescriptor(4, 0, sentinelBase, 384L, true);
        LOGGER.info("[dhvk] run35 sentinel registered via API: 0x{} (384B) -> scratch slot(4,0)",
                Long.toHexString(sentinelBase));
        LOGGER.info("[dhvk] run29 sentinel armed: all 6 table slots = sentinel descriptors "
                + "(row0.x = slot+1, sentinel@0x{}), creation-time table fully non-zero; "
                + "per-frame rewrite to real descriptors happens in dhvkBindHeap", sentinelBase);
    }

    /** 2 代 vkGetBufferDeviceAddress(run17 笔记: VVL 1.4.341 的 1 代 walker 有内部悬空指针 bug)。 */
    private static long bdaAddress2(long vkBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(vkBuffer);
            return VK12.vkGetBufferDeviceAddress(VkHandles.deviceWrapper, info);
        }
    }

    /** 一面 16×16 墙的四色角点(洋红/青/黄/绿),写入 4 个顶点。 */
    private static void putQuad(final ByteBuffer buf, final float x) {
        putVertex(buf, x, WALL_Y0, -WALL_HALF_Z, 255, 0, 255);  // 洋红
        putVertex(buf, x, WALL_Y1, -WALL_HALF_Z, 0, 255, 255);  // 青
        putVertex(buf, x, WALL_Y1, WALL_HALF_Z, 255, 255, 0);   // 黄
        putVertex(buf, x, WALL_Y0, WALL_HALF_Z, 0, 255, 0);     // 绿
    }

    /** 一面墙的两条三角形索引(6 个 short)。 */
    private static void putQuadIndices(final ByteBuffer buf, final int base) {
        buf.putShort((short) base);
        buf.putShort((short) (base + 1));
        buf.putShort((short) (base + 2));
        buf.putShort((short) base);
        buf.putShort((short) (base + 2));
        buf.putShort((short) (base + 3));
    }

    private static void putVertex(final ByteBuffer buf, final float x, final float y, final float z,
            final int r, final int g, final int b) {
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(z);
        buf.put((byte) r);
        buf.put((byte) g);
        buf.put((byte) b);
        buf.put((byte) 255);
    }
}
