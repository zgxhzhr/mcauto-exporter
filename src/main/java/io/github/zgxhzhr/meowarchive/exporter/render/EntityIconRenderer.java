package io.github.zgxhzhr.meowarchive.exporter.render;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 实体图标：客户端离屏渲染，默认 128×128。
 * 先按碰撞箱估一个比例渲染，再按实际像素范围重新缩放并居中，避免大体型实体被裁切。
 */
public final class EntityIconRenderer {
    private EntityIconRenderer() {
    }

    public static BufferedImage render(Class<? extends Entity> entityClass, int size) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || entityClass == null) {
            return null;
        }
        Entity entity = create(entityClass, mc);
        if (entity == null) {
            return null;
        }
        try {
            entity.setPosition(0.0D, 0.0D, 0.0D);
            float yaw = resolveYaw(entity);
            applyYaw(entity, yaw);
            entity.motionX = 0.0D;
            entity.motionY = 0.0D;
            entity.motionZ = 0.0D;
            entity.ticksExisted = 20;
            normalizeSpawnState(entity);

            float bbH = Math.max(entity.height, 0.2F);
            float bbW = Math.max(entity.width, 0.2F);
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
                boolean clipped = box[0] <= 0 || box[1] <= 0 || box[2] >= size - 1;
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
            ExporterLog.LOG.debug("实体渲染异常 {}: {}", entityClass.getName(), t.toString());
            return null;
        }
    }

    private static Entity create(Class<? extends Entity> entityClass, Minecraft mc) {
        try {
            Constructor<? extends Entity> ctor = entityClass.getConstructor(net.minecraft.world.World.class);
            return ctor.newInstance(mc.world);
        } catch (Throwable t) {
            ExporterLog.LOG.debug("实体创建失败 {}: {}", entityClass.getName(), t.toString());
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

    /**
     * 模型朝向：原版活体渲染器（RenderLivingBase）模型正面朝 +Z，30° 得到三分之四正面；
     * 其它自定义渲染器按 -Z 建模，同一变换下 30° 会转到侧面，取 15°。
     */
    private static float resolveYaw(Entity entity) {
        try {
            Object renderer = Minecraft.getMinecraft().getRenderManager().getEntityRenderObject(entity);
            if (renderer instanceof RenderLivingBase) {
                return 30.0F;
            }
        } catch (Throwable ignored) {
        }
        return 15.0F;
    }

    private static void applyYaw(Entity entity, float yaw) {
        entity.rotationYaw = yaw;
        entity.rotationPitch = 0.0F;
        entity.prevRotationYaw = yaw;
        entity.prevRotationPitch = 0.0F;
        entity.setRotationYawHead(yaw);
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            living.renderYawOffset = yaw;
            living.prevRenderYawOffset = yaw;
            living.prevRotationYawHead = yaw;
        }
    }

    private static BufferedImage draw(Entity entity, int size, float yaw, float tx, float ty, float scale) {
        Minecraft mc = Minecraft.getMinecraft();
        OffscreenTarget target = null;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);

            GlStateManager.pushMatrix();
            GlStateManager.translate(tx, ty, 0.0F);
            GlStateManager.scale(scale, -scale, scale);
            RenderHelper.enableStandardItemLighting();
            mc.getRenderManager().renderEntity(entity, 0.0D, 0.0D, 0.0D, yaw, 0.0F, false);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.popMatrix();

            GlStateManager.disableRescaleNormal();
            GlStateManager.disableDepth();
            return target.read();
        } catch (Throwable t) {
            ExporterLog.LOG.debug("实体离屏绘制失败: {}", t.toString());
            return null;
        } finally {
            if (target != null) {
                target.close();
            }
            RenderUtil.restoreGuiState();
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
