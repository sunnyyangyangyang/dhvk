package dev.dhvk;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.BlendFactor;
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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
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
    private static final Identifier PIPELINE_LOCATION = Identifier.fromNamespaceAndPath("dhvk", "pipeline/far_terrain");

    /**
     * S1 任务 2 几何堆源 BGL:phantom UBO 绑定 VBO/IBO(描述符 payload = 墙 VBO/IBO 的设备地址区间;
     * 驱动经堆描述符取地址,笔记 §1.6 裁决)。shader 侧对应
     * {@code layout(std140) uniform VBO { vec4 pad; }} 形块;官方 pushDescriptors 对缺失 uniform 抛异常,
     * 故 render() 里必须 setUniform 填充(双通道:classic push 描述符 + 堆 mapping 重定向)。
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
        .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE)))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();

    /** S1 任务 2: 几何描述符承载堆(持久,init 建一次;墙 A/B 的 VBO/IBO 地址注册在 cell 0)。 */
    private static final long CELL_CAPACITY = 8192L;

    private static GpuBuffer vertexBuffer;
    private static GpuBufferSlice vertexSlice;
    private static GpuBuffer indexBuffer;
    private static DescriptorHeap heap;
    /** 墙 VBO/IBO 的设备地址(注册进堆描述符的 payload)。 */
    private static long vboDeviceAddress = 0L;
    private static long iboDeviceAddress = 0L;
    /** phantom 绑定在合并 set layout 里的索引(首帧按名解析;-1 = 未就绪)。 */
    private static int vboBinding = -1;
    private static int iboBinding = -1;

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
        // S1 任务 2: 堆表只引用墙缓冲的设备地址(数字, 无对象依赖) → arena 之后、设备关闭之前关堆
        if (heap != null) {
            heap.close();
            heap = null;
        }
        vboDeviceAddress = 0L;
        iboDeviceAddress = 0L;
        vboBinding = -1;
        iboBinding = -1;
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

    /** 单帧绘制:两面远墙各一对四色三角形(16 块宽 × 16 块高)。 */
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
        GpuBufferSlice dynamicTransforms =
                RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();

        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        // S1 任务 2: wrapper 即堆绑定的发射点(笔记 §2); CBU 经 DhvkCommandEncoder
        // 探针接口读取(官方 wrapper 的 backend 访问器是 protected, mod 侧不可见)
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass renderPass = encoder.createRenderPass(
                () -> "FarTerrain", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            renderPass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            // phantom 绑定填充:官方 pushDescriptors 对合并 layout 的每个 UNIFORM_BUFFER 条目
            // 缺值即抛 "Missing uniform", 且恒写 classic 描述符 —— 堆 mapping 在其上重定向(笔记 §8)
            renderPass.setUniform("VBO", vertexSlice);
            renderPass.setUniform("IBO", indexBuffer.slice());
            renderPass.setVertexBuffer(0, vertexSlice);
            renderPass.setIndexBuffer(indexBuffer, IndexType.SHORT);
            // 堆源绑定:整表 + VBO/IBO 绑定的命令级 mapping(setPipeline 后、drawIndexed 前)
            dhvkBindHeap(encoder);
            renderPass.drawIndexed(12, 1, 0, 0, 0);
        }
    }

    /** S1 任务 2: 每帧在 encoder 当前 CBU 上绑整表 + phantom 绑定的堆源 mapping 链。 */
    private static void dhvkBindHeap(final CommandEncoder encoder) {
        if (heap == null) {
            return;
        }
        if (vboBinding < 0) {
            resolvePhantomBindings();
        }
        if (vboBinding < 0 || iboBinding < 0) {
            // 首帧竞态: 管线尚未编译 → 本帧退化为 classic 通道, 次帧自愈(不兜底不崩)
            return;
        }
        long cbu = ((DhvkCommandEncoder) (Object) encoder).dhvkCurrentCbu();
        if (cbu == 0L) {
            return;
        }
        // 整表绑定(rangeOffset=0 → mapping heapOffset = 表内绝对槽位偏移)
        heap.bind(cbu, 0L, heap.sizeBytes(),
                new DescriptorHeap.Mapping(vboBinding, heap.slotOffsetOf(0, 0)),
                new DescriptorHeap.Mapping(iboBinding, heap.slotOffsetOf(0, 1)));
    }

    /** 按名扫描合并 set layout 的条目, 定位 phantom 绑定索引(首帧 setPipeline 已完成管线编译)。 */
    private static void resolvePhantomBindings() {
        if (!(RenderSystem.getDevice().precompilePipeline(PIPELINE) instanceof VulkanRenderPipeline vrp)) {
            return;
        }
        List<com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.Entry> entries = vrp.layout().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals("VBO")) {
                vboBinding = i;
            } else if (entries.get(i).name().equals("IBO")) {
                iboBinding = i;
            }
        }
        if (vboBinding >= 0 && iboBinding >= 0) {
            LOGGER.info("[dhvk] phantom bindings resolved in merged layout: VBO={} IBO={} (total {} entries)",
                    vboBinding, iboBinding, entries.size());
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
