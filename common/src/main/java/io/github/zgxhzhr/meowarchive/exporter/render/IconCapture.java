package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 离屏图标捕获：新建 RGBA 纹理 → 用提交节点渲染 → GPU 回读 → 转 BufferedImage。
 *
 * <p>渲染走「提交节点 + FeatureRenderDispatcher」，回读是异步回调，
 * 所以 {@link #renderAsync} 通过 Consumer 交付结果，
 * 调用方需要在拿到结果前跳过后续帧的渲染请求。
 */
public final class IconCapture {
    /** 一次离屏绘制：往提交收集器里写几何体。 */
    @FunctionalInterface
    public interface Scene {
        void submit(PoseStack pose, SubmitNodeCollector collector);
    }

    private static final int COLOR_USAGE =
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                    | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final int DEPTH_USAGE =
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final int BUFFER_USAGE = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;

    private IconCapture() {
    }

    /**
     * @param size   输出边长（像素）
     * @param scale  模型单位（方块）→ 像素的缩放
     * @param flipY  Y 轴是否取负（与 flipZ 配合对齐原版两套姿态）
     * @param flipZ  Z 轴是否取负（实体走原版 PIP 姿态，物品走物品图集姿态）
     * @param centerX 模型原点在纹理中的 X 位置
     * @param centerY 模型原点在纹理中的 Y 位置
     * @param scene  往提交收集器写几何体的回调
     * @param done   渲染结果回调（失败时为 null）
     */
    public static void renderAsync(int size, float scale, boolean flipY, boolean flipZ, float centerX, float centerY,
                                   Scene scene, Consumer<BufferedImage> done) {
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture color = null;
        GpuTextureView colorView = null;
        GpuTexture depth = null;
        GpuTextureView depthView = null;
        CachedOrthoProjectionMatrixBuffer projectionBuffer = null;
        try {
            color = device.createTexture(() -> "meowarchive icon", COLOR_USAGE, TextureFormat.RGBA8,
                    size, size, 1, 1);
            colorView = device.createTextureView(color);
            depth = device.createTexture(() -> "meowarchive icon depth", DEPTH_USAGE, TextureFormat.DEPTH32,
                    size, size, 1, 1);
            depthView = device.createTextureView(depth);
            // 深度必须清成 1.0（最远），清 0 会让所有片元都过不了深度测试
            device.createCommandEncoder().clearColorAndDepthTextures(color, 0, depth, 1.0);

            projectionBuffer = new CachedOrthoProjectionMatrixBuffer("meowarchive icon", -1000.0F, 1000.0F, true);
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(size, size), ProjectionType.ORTHOGRAPHIC);

            RenderSystem.outputColorTextureOverride = colorView;
            RenderSystem.outputDepthTextureOverride = depthView;
            // 原版 GuiItemAtlas 在绘制前会显式重置裁剪框，否则会沿用上一处 GUI 的 scissor 把画面裁空
            RenderSystem.enableScissorForRenderTypeDraws(0, 0, size, size);
            try {
                PoseStack pose = new PoseStack();
                pose.translate(centerX, centerY, 0.0F);
                // 物品对齐 GuiItemAtlas（Y 负、Z 正）；实体对齐 PictureInPictureRenderer（Y 正、Z 负）
                pose.scale(scale, flipY ? -scale : scale, flipZ ? -scale : scale);
                FeatureRenderDispatcher dispatcher = Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher();
                SubmitNodeStorage nodes = dispatcher.getSubmitNodeStorage();
                scene.submit(pose, nodes);
                dispatcher.renderAllFeatures();
                // 26.1.2 的提交节点最终落在 RenderBuffers 的批次里，需要显式 endBatch 才会真正绘制
                Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } finally {
                RenderSystem.disableScissorForRenderTypeDraws();
                RenderSystem.outputColorTextureOverride = null;
                RenderSystem.outputDepthTextureOverride = null;
            }

            GpuTexture source = color;
            GpuTextureView sourceView = colorView;
            GpuTexture depthTexture = depth;
            GpuTextureView depthTextureView = depthView;
            CachedOrthoProjectionMatrixBuffer projBuffer = projectionBuffer;
            GpuBuffer buffer = device.createBuffer(() -> "meowarchive icon buffer", BUFFER_USAGE,
                    (long) size * size * 4);
            device.createCommandEncoder().copyTextureToBuffer(source, buffer, 0L, () -> {
                try {
                    BufferedImage image;
                    try (GpuBuffer.MappedView view = device.createCommandEncoder().mapBuffer(buffer, true, false)) {
                        image = toImage(view, size);
                    }
                    done.accept(image);
                } catch (Throwable t) {
                    done.accept(null);
                } finally {
                    buffer.close();
                    sourceView.close();
                    source.close();
                    depthTextureView.close();
                    depthTexture.close();
                    projBuffer.close();
                }
            }, 0);
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("离屏图标渲染失败", t);
            if (projectionBuffer != null) {
                projectionBuffer.close();
            }
            if (depthView != null) {
                depthView.close();
            }
            if (depth != null) {
                depth.close();
            }
            if (colorView != null) {
                colorView.close();
            }
            if (color != null) {
                color.close();
            }
            done.accept(null);
        }
    }

    /** 把回读缓冲转成 ARGB BufferedImage，保留透明度。 */
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
