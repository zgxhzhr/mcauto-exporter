package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * 离屏图标捕获。
 *
 * <p>1.21.5 的渲染管线里，RenderType 的目标由 {@code RenderStateShard.OutputStateShard} 的
 * supplier 决定（通常是主渲染目标）。这里用 Mixin 在捕获期间把 supplier 换成自建的
 * {@link TextureTarget}，画完再把颜色纹理回读到 CPU，转成 {@link BufferedImage}。
 *
 * <p>回读（copyTextureToBuffer）是异步的，结果通过 {@code done} 回调交付，
 * 调用方需要在拿到结果前跳过后续帧的渲染请求。
 */
public final class IconCapture {

    @FunctionalInterface
    public interface Scene {
        void draw(GuiGraphics graphics);
    }

    /** 捕获期间被 Mixin 读取；非捕获期间为 null。 */
    private static RenderTarget override;

    private IconCapture() {
    }

    /** 供 Mixin 调用：当前捕获目标，没有捕获时返回 null。 */
    public static RenderTarget overrideTarget() {
        return override;
    }

    public static void renderAsync(int size, Scene scene, Consumer<BufferedImage> done) {
        Minecraft mc = Minecraft.getInstance();
        GpuDevice device = RenderSystem.getDevice();
        TextureTarget target = null;
        try {
            target = new TextureTarget("meowarchive icon", size, size, true);
            CommandEncoder encoder = device.createCommandEncoder();
            // 深度必须清成 1.0（远平面），清 0 会让所有几何体被深度测试丢弃
            encoder.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);

            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, 21000.0F),
                    ProjectionType.ORTHOGRAPHIC);
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            // 原版 GUI 阶段栈顶已经是 translate(0,0,-11000)（GameRenderer 里 push 后不 pop），
            // 这里不能再叠加一次，否则几何体会被推到远裁面之外而全部被裁掉。
            float ambientZ = modelView.m32();
            modelView.pushMatrix();
            modelView.translate(0.0F, 0.0F, -11000.0F - ambientZ);

            // HUD 阶段可能还留着 GUI 的裁剪框，会按屏幕坐标裁掉离屏画布上的内容
            ScissorState scissor = RenderSystem.SCISSOR_STATE;
            boolean scissorEnabled = scissor.isEnabled();
            int scissorX = scissor.getX();
            int scissorY = scissor.getY();
            int scissorWidth = scissor.getWidth();
            int scissorHeight = scissor.getHeight();
            RenderSystem.disableScissor();

            override = target;
            try {
                // 上一轮可能还有未提交的批次，先冲到当前主目标，避免串进本次离屏纹理
                mc.renderBuffers().bufferSource().endBatch();
                // HUD 阶段的光照可能被其它 HUD 元素留在「平面物品」模式：
                // 此时 3D 方块物品只剩 0.4 的环境光，整体亮度只有应有的一半。
                // 显式恢复 GUI 的 3D 物品光照，平面物品仍由 GuiGraphics 自己切到 ITEMS_FLAT。
                com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
                GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                scene.draw(graphics);
                graphics.flush();
            } finally {
                override = null;
                if (scissorEnabled) {
                    RenderSystem.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
                }
                modelView.popMatrix();
                RenderSystem.restoreProjectionMatrix();
            }

            TextureTarget captured = target;
            GpuBuffer buffer = device.createBuffer(() -> "meowarchive icon buffer",
                    BufferType.PIXEL_PACK, BufferUsage.STATIC_READ, size * size * 4);
            encoder.copyTextureToBuffer(captured.getColorTexture(), buffer, 0, () -> {
                BufferedImage image = null;
                try {
                    image = toImage(encoder, buffer, size);
                } catch (Throwable t) {
                    io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("图标回读失败", t);
                } finally {
                    buffer.close();
                    captured.destroyBuffers();
                }
                done.accept(image);
            }, 0);
            target = null;
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("离屏图标渲染失败", t);
            if (target != null) {
                target.destroyBuffers();
            }
            done.accept(null);
        } finally {
            override = null;
        }
    }

    /** 把回读缓冲转成 ARGB BufferedImage，保留透明度并翻转行序。 */
    private static BufferedImage toImage(CommandEncoder encoder, GpuBuffer buffer, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        try (GpuBuffer.ReadView view = encoder.readBuffer(buffer)) {
            ByteBuffer data = view.data();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int v = data.getInt((x + y * size) * 4);
                    int r = v & 0xFF;
                    int g = (v >>> 8) & 0xFF;
                    int b = (v >>> 16) & 0xFF;
                    int a = (v >>> 24) & 0xFF;
                    image.setRGB(x, size - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
        }
        return image;
    }
}
