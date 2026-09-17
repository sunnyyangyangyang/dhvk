package dev.dhvk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalDouble;

/**
 * S2 离屏目标对 (DH 式移植): 颜色 RGBA8_UNORM + 深度 D32_FLOAT, 与主视口同尺寸,
 * 视口变化才重建 (照 BlazeTextureWrapper.tryCreateOrResize 的官方调用形)。
 * TAIL 钩子在此对上画我们的体素带; S3 的全屏扇再把它合成回主目标。
 */
public final class DhVkOffscreen {

    private static final Logger LOGGER = LoggerFactory.getLogger("DhVkOffscreen");

    private static final int USAGE = GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private final GpuDevice device;
    private GpuTexture colorTex;
    private GpuTexture depthTex;
    private GpuTextureView colorView;
    private GpuTextureView depthView;
    private GpuSampler sampler;
    private int width = -1;
    private int height = -1;

    public DhVkOffscreen() {
        this.device = RenderSystem.getDevice();
    }

    /** 视口尺寸未变则原样复用; 变化则关闭旧纹理重建。 */
    public boolean tryCreateOrResize(final int w, final int h) {
        if (this.width == w && this.height == h && this.colorTex != null) {
            return false;
        }
        closeTextures();
        this.width = w;
        this.height = h;
        this.colorTex = this.device.createTexture(() -> "dhvk/offscreen_color", USAGE,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        this.depthTex = this.device.createTexture(() -> "dhvk/offscreen_depth", USAGE,
                GpuFormat.D32_FLOAT, w, h, 1, 1);
        this.colorView = this.device.createTextureView(this.colorTex);
        this.depthView = this.device.createTextureView(this.depthTex);
        LOGGER.info("[dhvk] offscreen targets (re)created: {}x{}", w, h);
        return true;
    }

    public GpuSampler ensureSampler() {
        if (this.sampler == null) {
            this.sampler = this.device.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        }
        return this.sampler;
    }

    /** 每帧开 pass 前清: 颜色透明黑, 深度 1.0 (未画到 = 合成扇 discard 的判据)。 */
    public void clear(final CommandEncoder encoder) {
        if (this.colorTex != null) {
            encoder.clearColorTexture(this.colorTex, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
            encoder.clearDepthTexture(this.depthTex, 1.0d);
        }
    }

    public boolean isReady() {
        return this.colorView != null && this.depthView != null;
    }

    private void closeTextures() {
        if (this.colorView != null) {
            this.colorView.close();
            this.colorView = null;
        }
        if (this.depthView != null) {
            this.depthView.close();
            this.depthView = null;
        }
        if (this.colorTex != null) {
            this.colorTex.close();
            this.colorTex = null;
        }
        if (this.depthTex != null) {
            this.depthTex.close();
            this.depthTex = null;
        }
        if (this.sampler != null) {
            this.sampler.close();
            this.sampler = null;
        }
    }

    public GpuTextureView colorView() {
        return this.colorView;
    }

    public GpuTextureView depthView() {
        return this.depthView;
    }
}
