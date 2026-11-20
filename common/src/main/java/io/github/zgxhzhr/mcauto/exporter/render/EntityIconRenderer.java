package io.github.zgxhzhr.mcauto.exporter.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * 实体图标：客户端离屏渲染，128×128。渲染器依赖运行时状态的实体会渲染失败或失真，由上层标记 suspect。
 */
public final class EntityIconRenderer {
    private EntityIconRenderer() {
    }

    public static BufferedImage render(EntityType<?> type, int size, float yaw) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        Entity entity;
        try {
            entity = type.create(mc.level);
        } catch (Throwable t) {
            return null;
        }
        if (entity == null) {
            return null;
        }
        TextureTarget target = null;
        ByteBuffer buffer = null;
        MultiBufferSource.BufferSource buffers = null;
        try {
            entity.setPos(0.0, 0.0, 0.0);
            entity.setYRot(yaw);
            entity.setXRot(0.0F);
            entity.setYHeadRot(yaw);
            entity.setOldPosAndRot();
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            entity.setNoGravity(true);
            entity.tickCount = 20;

            target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, 21000.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);

            buffers = mc.renderBuffers().bufferSource();
            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.translate(0.0F, 0.0F, -11000.0F);
            pose.translate(size / 2.0F, size * 0.92F, 0.0F);
            float scale = size / 3.2F;
            pose.scale(scale, -scale, scale);
            mc.getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0, yaw, 0.0F, pose,
                    buffers, 0xF000F0);
            buffers.endBatch();
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
            return null;
        } finally {
            if (buffers != null) {
                try {
                    buffers.endBatch();
                } catch (Throwable ignored) {
                }
            }
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
