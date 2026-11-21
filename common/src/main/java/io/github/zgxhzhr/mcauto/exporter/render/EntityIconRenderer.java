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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * 实体图标：客户端离屏渲染，默认 128×128。
 * 先按碰撞箱估一个比例渲染，再按实际像素范围重新缩放并居中，避免大体型实体被裁切。
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
        try {
            entity.setPos(0.0, 0.0, 0.0);
            entity.setYRot(yaw);
            entity.setXRot(0.0F);
            entity.setYHeadRot(yaw);
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                living.yBodyRot = yaw;
                living.yBodyRotO = yaw;
            }
            entity.setOldPosAndRot();
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            entity.setNoGravity(true);
            entity.tickCount = 20;
            normalizeSpawnState(entity);

            float bbH = Math.max(entity.getBbHeight(), 0.2F);
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            float fit = size * 0.86F;
            float scale = Math.min(fit / bbH, fit / (bbW * 2.2F));
            float maxScale = size * 1.2F;
            scale = Math.min(scale, maxScale);

            float tx = size / 2.0F;
            float ty = size * 0.94F;
            BufferedImage image = draw(entity, size, yaw, tx, ty, scale);
            if (image == null) {
                return null;
            }
            // 迭代校正：内容触边说明已溢出，先缩小；不再触边后按实际像素范围缩放并居中
            float target = size * 0.88F;
            for (int i = 0; i < 6; i++) {
                int[] box = bounds(image);
                if (box == null) {
                    return null;
                }
                boolean clipped = box[0] <= 0 || box[1] <= 0 || box[2] >= size - 1 || box[3] >= size - 1;
                float contentW = box[2] - box[0] + 1;
                float contentH = box[3] - box[1] + 1;
                float newScale;
                if (clipped) {
                    newScale = scale * 0.6F;
                } else {
                    float k = Math.min(target / contentW, target / contentH);
                    newScale = Math.min(scale * k, maxScale);
                    if (Math.abs(newScale / scale - 1.0F) < 0.03F) {
                        break;
                    }
                }
                float k = newScale / scale;
                float centerX = (box[0] + box[2]) / 2.0F;
                tx = size / 2.0F - k * (centerX - tx);
                ty = ty + k * (ty - box[3]);
                scale = newScale;
                BufferedImage next = draw(entity, size, yaw, tx, ty, scale);
                if (next == null) {
                    break;
                }
                image = next;
            }
            // 收尾对齐：把内容底边落到基线、水平居中，避免最后一像素贴边
            int[] finalBox = bounds(image);
            if (finalBox != null) {
                float centerX = (finalBox[0] + finalBox[2]) / 2.0F;
                float alignTx = size / 2.0F - (centerX - tx);
                float alignTy = ty + (ty - finalBox[3]);
                if (Math.abs(alignTx - tx) > 0.5F || Math.abs(alignTy - ty) > 0.5F) {
                    BufferedImage aligned = draw(entity, size, yaw, alignTx, alignTy, scale);
                    if (aligned != null) {
                        image = aligned;
                    }
                }
            }
            return image;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 裸建实体（未经 spawn）时，部分模组的生物会停留在 spawn 动画首帧（模型埋在地下或被隐藏），
     * 截出来是全透明。这里把常见的“已生成”开关置为 true，让模型回到待机姿态。
     * 只调用模组自带的公开 setter，不做 Minecraft 内部字段反射。
     */
    private static void normalizeSpawnState(Entity entity) {
        for (String name : new String[]{"setHasSpawned", "setSpawned"}) {
            try {
                Method setter = entity.getClass().getMethod(name, boolean.class);
                setter.invoke(entity, true);
                return;
            } catch (NoSuchMethodException ignored) {
                // 该实体没有这个开关，继续试下一个
            } catch (Throwable ignored) {
                // 调用失败不影响正常渲染
            }
        }
    }

    private static BufferedImage draw(Entity entity, int size, float yaw, float tx, float ty, float scale) {
        Minecraft mc = Minecraft.getInstance();
        TextureTarget target = null;
        ByteBuffer buffer = null;
        MultiBufferSource.BufferSource buffers = null;
        try {
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
            pose.translate(tx, ty, 0.0F);
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

    /** 返回内容边界 {x0, y0, x1, y1}，无内容返回 null。 */
    private static int[] bounds(BufferedImage image) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    if (x < x0) {
                        x0 = x;
                    }
                    if (x > x1) {
                        x1 = x;
                    }
                    if (y < y0) {
                        y0 = y;
                    }
                    if (y > y1) {
                        y1 = y;
                    }
                }
            }
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }
}
