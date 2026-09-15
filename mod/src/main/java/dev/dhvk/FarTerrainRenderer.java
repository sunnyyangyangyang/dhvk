package dev.dhvk;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.resources.Identifier;
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

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(PIPELINE_LOCATION)
        .withVertexShader(SHADER)
        .withFragmentShader(SHADER)
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE)))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();

    private static GpuBuffer vertexBuffer;
    private static GpuBufferSlice vertexSlice;
    private static GpuBuffer indexBuffer;

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
    }

    /** 把远几何 pass 挂进官方帧图:与云 pass 同款,读写主目标(共享深度)。 */
    public static void attach(final FrameGraphBuilder frame, final LevelTargetBundle targets) {
        FramePass pass = frame.addPass("far_terrain");
        targets.main = pass.readsAndWrites(targets.main);
        pass.executes(FarTerrainRenderer::render);
    }

    /** 单帧绘制:两面远墙各一对四色三角形(16 块宽 × 16 块高)。 */
    public static void render() {
        ensureBuffers();
        GpuBufferSlice dynamicTransforms =
                RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();

        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "FarTerrain", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            renderPass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertexSlice);
            renderPass.setIndexBuffer(indexBuffer, IndexType.SHORT);
            renderPass.drawIndexed(12, 1, 0, 0, 0);
        }
    }

    private static synchronized void ensureBuffers() {
        if (vertexBuffer != null) {
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

        vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_vbo",
                GpuBuffer.USAGE_VERTEX, vbo);
        vertexSlice = vertexBuffer.slice();
        indexBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_ibuffer",
                GpuBuffer.USAGE_INDEX, ibo);
        LOGGER.info("[dhvk] S0 far-terrain buffers created (vbo={}B, ibo={}B)", vbo.capacity(), ibo.capacity());
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
