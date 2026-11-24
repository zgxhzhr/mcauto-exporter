package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4fStack;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 离屏图标捕获（1.21.8）。
 *
 * <p>1.21.6 起 GUI 改成分层渲染状态（GuiRenderState + GuiRenderer），不再有可以直接
 * flush 的 GuiGraphics 批次。这里绕过 GUI 层，直接建离屏颜色/深度纹理，
 * 用 {@link RenderSystem#outputColorTextureOverride} 把后续 RenderType 批次导到离屏纹理，
 * 画完再异步回读到 CPU 转成 {@link BufferedImage}。
 *
 * <p>回读（copyTextureToBuffer + mapBuffer）是异步的，结果通过 {@code done} 回调交付。
 */
public final class IconCapture {

    /** 一次离屏绘制：在给定姿态和批次里写几何体。 */
    @FunctionalInterface
    public interface Scene {
        void draw(PoseStack pose, MultiBufferSource.BufferSource buffers);
    }

    private static final int COLOR_USAGE =
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                    | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final int DEPTH_USAGE =
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final int BUFFER_USAGE = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;

    /**
     * @param size    输出边长（像素）
     * @param scale   模型单位（方块）→ 像素的 X/Y 缩放
     * @param zScale  模型单位 → 像素的 Z 缩放（物品保持 1，避免改变法线导致光照偏亮）
     * @param flipY   Y 轴是否取负（物品按 GUI 图集的姿态绘制）
     * @param flipZ   Z 轴是否取负
     * @param centerX 模型原点在纹理中的 X 位置
     * @param centerY 模型原点在纹理中的 Y 位置
     */
    public static void renderAsync(int size, float scale, float zScale, boolean flipY, boolean flipZ,
                                   float centerX, float centerY, Scene scene, Consumer<BufferedImage> done) {
        GpuDevice device = RenderSystem.getDevice();
        Minecraft mc = Minecraft.getInstance();
        GpuTexture color = null;
        GpuTextureView colorView = null;
        GpuTexture depth = null;
        GpuTextureView depthView = null;
        CachedOrthoProjectionMatrixBuffer projection = null;
        try {
            color = device.createTexture(() -> "meowarchive icon", COLOR_USAGE, TextureFormat.RGBA8,
                    size, size, 1, 1);
            colorView = device.createTextureView(color);
            depth = device.createTexture(() -> "meowarchive icon depth", DEPTH_USAGE, TextureFormat.DEPTH32,
                    size, size, 1, 1);
            depthView = device.createTextureView(depth);
            // 深度必须清成 1.0（最远），清 0 会让所有片元都过不了深度测试
            device.createCommandEncoder().clearColorAndDepthTextures(color, 0, depth, 1.0);

            RenderSystem.backupProjectionMatrix();
            // 与原版 GUI 物品图集一致的深度范围（-1000 ~ 1000）
            projection = new CachedOrthoProjectionMatrixBuffer("meowarchive icon", -1000.0F, 1000.0F, true);
            RenderSystem.setProjectionMatrix(projection.getBuffer(size, size), ProjectionType.ORTHOGRAPHIC);

            GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
            GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
            RenderSystem.outputColorTextureOverride = colorView;
            RenderSystem.outputDepthTextureOverride = depthView;

            // HUD 阶段可能还留着 GUI 的裁剪框，会按屏幕坐标裁掉离屏画布上的内容
            ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            boolean scissorEnabled = scissor.enabled();
            int scissorX = scissor.x();
            int scissorY = scissor.y();
            int scissorWidth = scissor.width();
            int scissorHeight = scissor.height();
            RenderSystem.enableScissorForRenderTypeDraws(0, 0, size, size);

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            try {
                PoseStack pose = new PoseStack();
                pose.translate(centerX, centerY, 0.0F);
                pose.scale(scale, flipY ? -scale : scale, flipZ ? -zScale : zScale);
                MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
                // 上一轮可能还有未提交的批次，先冲到当前目标，避免串进本次离屏纹理
                buffers.endBatch();
                scene.draw(pose, buffers);
                buffers.endBatch();
            } finally {
                modelView.popMatrix();
                if (scissorEnabled) {
                    RenderSystem.enableScissorForRenderTypeDraws(scissorX, scissorY, scissorWidth, scissorHeight);
                } else {
                    RenderSystem.disableScissorForRenderTypeDraws();
                }
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.outputColorTextureOverride = previousColor;
                RenderSystem.outputDepthTextureOverride = previousDepth;
            }

            GpuTexture source = color;
            GpuTextureView sourceView = colorView;
            GpuTexture depthTexture = depth;
            GpuTextureView depthTextureView = depthView;
            CachedOrthoProjectionMatrixBuffer projectionBuffer = projection;
            GpuBuffer buffer = device.createBuffer(() -> "meowarchive icon buffer", BUFFER_USAGE,
                    size * size * 4);
            device.createCommandEncoder().copyTextureToBuffer(source, buffer, 0, () -> {
                BufferedImage image = null;
                try (GpuBuffer.MappedView view = device.createCommandEncoder().mapBuffer(buffer, true, false)) {
                    image = toImage(view, size);
                } catch (Throwable t) {
                    io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("图标回读失败", t);
                } finally {
                    buffer.close();
                    sourceView.close();
                    source.close();
                    depthTextureView.close();
                    depthTexture.close();
                    projectionBuffer.close();
                }
                done.accept(image);
            }, 0);
            color = null;
            colorView = null;
            depth = null;
            depthView = null;
            projection = null;
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("离屏图标渲染失败", t);
            closeQuietly(projection);
            closeQuietly(depthView);
            closeQuietly(depth);
            closeQuietly(colorView);
            closeQuietly(color);
            done.accept(null);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
                // 释放失败不影响后续任务
            }
        }
    }

    /** 把回读缓冲转成 ARGB BufferedImage，保留透明度并翻转行序。 */
    private static BufferedImage toImage(GpuBuffer.MappedView view, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = view.data().getInt((x + y * size) * 4);
                int a = (v >>> 24) & 0xFF;
                int b = (v >>> 16) & 0xFF;
                int g = (v >>> 8) & 0xFF;
                int r = v & 0xFF;
                image.setRGB(x, size - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }
}
