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
 * S0 验证渲染器:在 4000 块外画一面 16×16 彩色墙(顶点色,无光照),
 * 走官方 26.2 帧图的一个自有 pass,共享主渲染目标的颜色+深度纹理。
 *
 * <p>验收目标(对照 vanilla VVL 零错误基线):
 * <ol>
 *   <li>远处出现四色墙(证明超远几何经官方管线出图);</li>
 *   <li>近处山丘/地形能遮挡它(共享 reverse-Z 深度);</li>
 *   <li>RD256 下它位于雾距之内,按官方 apply_fog 出雾;</li>
 *   <li>VVL 全程零报错。</li>
 * </ol>
 *
 * <p>机制完全照抄官方 CloudRenderer 的远物 pass 模板:
 * 默认 uniform 绑定 + writeTransform 动态矩阵 + createRenderPass(主目标颜色/深度)。
 */
public final class FarTerrainRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarTerrainRenderer.class);

    /** 远墙中心 X 偏移(块):-X 方向 4000 块。 */
    private static final float FAR_X = -4000.0F;
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

    /** 把远几何 pass 挂进官方帧图:与云 pass 同款,读写主目标(共享深度)。 */
    public static void attach(final FrameGraphBuilder frame, final LevelTargetBundle targets) {
        FramePass pass = frame.addPass("far_terrain");
        targets.main = pass.readsAndWrites(targets.main);
        pass.executes(FarTerrainRenderer::render);
    }

    /** 单帧绘制:远墙四色三角形对(2×16 块宽 × 16 块高)。 */
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
            renderPass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    private static synchronized void ensureBuffers() {
        if (vertexBuffer != null) {
            return;
        }

        // 4 个四色角点:vec3 位置(块,float32)+ vec4 颜色(RGBA8_UNORM 字节序)。
        ByteBuffer vbo = ByteBuffer.allocateDirect(4 * (3 * 4 + 4)).order(ByteOrder.LITTLE_ENDIAN);
        putVertex(vbo, FAR_X, WALL_Y0, -WALL_HALF_Z, 255, 0, 255); // 洋红
        putVertex(vbo, FAR_X, WALL_Y1, -WALL_HALF_Z, 0, 255, 255); // 青
        putVertex(vbo, FAR_X, WALL_Y1, WALL_HALF_Z, 255, 255, 0);  // 黄
        putVertex(vbo, FAR_X, WALL_Y0, WALL_HALF_Z, 0, 255, 0);    // 绿
        vbo.rewind();

        ByteBuffer ibo = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.LITTLE_ENDIAN);
        ibo.putShort((short) 0).putShort((short) 1).putShort((short) 2)
           .putShort((short) 0).putShort((short) 2).putShort((short) 3);
        ibo.rewind();

        vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_vbo",
                GpuBuffer.USAGE_VERTEX, vbo);
        vertexSlice = vertexBuffer.slice();
        indexBuffer = RenderSystem.getDevice().createBuffer(() -> "dhvk/far_terrain_ibuffer",
                GpuBuffer.USAGE_INDEX, ibo);
        LOGGER.info("[dhvk] S0 far-terrain buffers created (vbo={}B, ibo={}B)", vbo.capacity(), ibo.capacity());
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
