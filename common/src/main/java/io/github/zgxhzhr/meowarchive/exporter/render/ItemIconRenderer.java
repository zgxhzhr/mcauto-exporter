package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * 物品 / 方块物品图标：GUI 阶段离屏渲染，走原版物品栏管线。
 */
public final class ItemIconRenderer {
    private ItemIconRenderer() {
    }

    public static BufferedImage render(ItemStack stack, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (stack.isEmpty()) {
            return null;
        }
        TextureTarget target = null;
        ByteBuffer buffer = null;
        try {
            target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, 21000.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);

            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            PoseStack pose = graphics.pose();
            pose.pushPose();
            // 1.21.1 的 GUI modelview 已由 GameRenderer 平移 -11000，这里不能再加
            pose.translate(size / 2.0F, size / 2.0F, 0.0F);
            pose.scale(size / 16.0F, size / 16.0F, 1.0F);
            graphics.renderItem(stack, -8, -8);
            graphics.flush();
            pose.popPose();

            buffer = MemoryUtil.memAlloc(size * size * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            byte[] row = new byte[size * 4];
            boolean any = false;
            for (int y = 0; y < size; y++) {
                int srcRow = (size - 1 - y) * size * 4;
                buffer.position(srcRow);
                buffer.get(row);
                for (int x = 0; x < size; x++) {
                    int r = row[x * 4] & 0xFF;
                    int g = row[x * 4 + 1] & 0xFF;
                    int b = row[x * 4 + 2] & 0xFF;
                    int a = row[x * 4 + 3] & 0xFF;
                    if (a > 8) {
                        any = true;
                    }
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return any ? image : null;
        } catch (Throwable t) {
            ExporterLog.LOG.error("物品渲染异常: " + stack.getItem(), t);
            return null;
        } finally {
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }
            if (target != null) {
                target.unbindWrite();
                target.destroyBuffers();
            }
            Minecraft main = Minecraft.getInstance();
            main.getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F,
                            main.getWindow().getGuiScaledWidth(),
                            main.getWindow().getGuiScaledHeight(),
                            0.0F, 1000.0F, 21000.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
