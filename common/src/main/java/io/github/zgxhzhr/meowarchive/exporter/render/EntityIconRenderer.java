package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 实体图标：客户端离屏渲染。
 * 先画在一张放大一倍的画布上（保证翅膀、披风等超出碰撞箱的部分不被裁掉），
 * 再在纯 Java 侧裁出实际内容、缩放居中成目标尺寸。
 *
 * <p>批量导出走 {@link #renderAsync(EntityType, int, Consumer)}（默认参数，行为与之前一致）；
 * 单实体预览/导出走 {@link #renderAsync(Entity, int, EntityRenderOptions, Consumer)}，可调角度、俯仰、
 * 缩放、偏移、亮度、阴影与背景。
 */
public final class EntityIconRenderer {
    private static final int FULL_BRIGHT = 0xF000F0;

    private EntityIconRenderer() {
    }

    public static void renderAsync(EntityType<?> type, int size, Consumer<BufferedImage> done) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            done.accept(null);
            return;
        }
        Entity entity;
        try {
            entity = type.create(mc.level, EntitySpawnReason.COMMAND);
        } catch (Throwable t) {
            done.accept(null);
            return;
        }
        if (entity == null) {
            done.accept(null);
            return;
        }
        renderAsync(entity, size, new EntityRenderOptions(), done);
    }

    /** 用指定参数渲染实体，结果通过回调交付（渲染本身是异步的）。 */
    public static void renderAsync(Entity source, int size, EntityRenderOptions options,
                                   Consumer<BufferedImage> done) {
        if (source == null || options == null || size <= 0) {
            done.accept(null);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            done.accept(null);
            return;
        }
        try {
            Entity entity = source;
            float yaw = options.yaw == null ? resolveYaw(entity) : options.yaw;
            float headYaw = options.headYaw == null ? yaw : options.headYaw;
            entity.setPos(0.0, 0.0, 0.0);
            entity.setYRot(yaw);
            entity.setXRot(0.0F);
            entity.setYHeadRot(headYaw);
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                living.yBodyRot = yaw;
                living.yBodyRotO = yaw;
                living.yHeadRot = headYaw;
                living.yHeadRotO = headYaw;
            }
            entity.setOldPosAndRot();
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            entity.setNoGravity(true);
            entity.tickCount = Math.max(0, options.tick);
            normalizeSpawnState(entity);

            float bbH = Math.max(entity.getBbHeight(), 0.2F);
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            int canvas = Math.max(size * 2, 128);
            float scale = canvas * 0.5F / Math.max(bbW, bbH);
            float center = canvas / 2.0F;
            float modelHeight = bbH;

            IconCapture.renderAsync(canvas, graphics -> {
                PoseStack pose = graphics.pose();
                pose.pushPose();
                pose.translate(center, center, 0.0F);
                pose.scale(scale, -scale, scale);
                // 模型原点在脚底、身体向上延伸，先下移半个身高让内容落在画布内
                pose.translate(0.0F, -modelHeight / 2.0F, 0.0F);
                if (options.pitch != 0.0F) {
                    pose.mulPose(Axis.XP.rotationDegrees(options.pitch));
                }
                Lighting.setupForEntityInInventory();
                mc.getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0, 0.0F, pose,
                        mc.renderBuffers().bufferSource(), FULL_BRIGHT);
                graphics.flush();
                Lighting.setupFor3DItems();
                pose.popPose();
            }, raw -> {
                if (raw == null || ImageUtil.blank(raw)) {
                    done.accept(null);
                } else {
                    done.accept(finish(raw, size, options));
                }
            });
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("实体渲染异常 " + source.getType(), t);
            done.accept(null);
        }
    }

    /** 默认朝向：原版（LivingEntityRenderer）模型按 +Z 建模，取 330° 得到三分之四正面；
     * GeckoLib 等自定义渲染器的模型按 -Z 建模，同一变换下 330° 正好转到正侧面，
     * 取 15° 才能让脸朝向镜头。 */
    public static float defaultYaw(Entity entity) {
        return resolveYaw(entity);
    }

    /** 裁剪到内容、按画布比例缩放，再叠加缩放/偏移/亮度/阴影/背景。 */
    private static BufferedImage finish(BufferedImage image, int size, EntityRenderOptions options) {
        float fill = options.autoFit ? clamp(options.margin, 0.4F, 1.0F) : 0.94F;
        BufferedImage fitted = ImageUtil.fitContent(image, size, fill);
        if (fitted == null) {
            return null;
        }
        boolean plain = options.zoom == 1.0F && options.offsetX == 0.0F && options.offsetY == 0.0F
                && options.brightness == 1.0F && !options.shadow && options.transparent;
        if (plain) {
            return fitted;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            if (!options.transparent) {
                g.setColor(new Color(options.background, true));
                g.fillRect(0, 0, size, size);
            }
            if (options.shadow) {
                int[] box = ImageUtil.bounds(fitted);
                int contentW = box == null ? (int) (size * 0.4F) : box[2] - box[0] + 1;
                int w = Math.max(6, (int) (contentW * 0.9F));
                int h = Math.max(3, (int) (w * 0.22F));
                int cx = box == null ? size / 2 : (box[0] + box[2]) / 2;
                int cy = box == null ? (int) (size * 0.94F) : box[3];
                g.setColor(new Color(0, 0, 0, 72));
                g.fillOval(cx - w / 2, cy - h / 2, w, h);
            }
            float zoom = clamp(options.zoom, 0.2F, 4.0F);
            int dw = Math.max(1, Math.round(size * zoom));
            int dh = Math.max(1, Math.round(size * zoom));
            int dx = (size - dw) / 2 + Math.round(options.offsetX * size);
            int dy = (size - dh) / 2 - Math.round(options.offsetY * size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    zoom > 1.0F ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(fitted, dx, dy, dw, dh, null);
        } finally {
            g.dispose();
        }
        if (options.brightness != 1.0F) {
            float b = clamp(options.brightness, 0.1F, 2.0F);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = out.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a == 0) {
                        continue;
                    }
                    int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * b));
                    int gg = Math.min(255, Math.round(((argb >> 8) & 0xFF) * b));
                    int bb = Math.min(255, Math.round((argb & 0xFF) * b));
                    out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | bb);
                }
            }
        }
        return out;
    }

    /** 裸建实体（未经 spawn）时把常见的“已生成”开关置为 true，让模型回到待机姿态。 */
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

    private static float resolveYaw(Entity entity) {
        try {
            if (Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity)
                    instanceof LivingEntityRenderer<?, ?, ?>) {
                return 330.0F;
            }
        } catch (Throwable ignored) {
            // 取不到渲染器时按自定义模型处理
        }
        return 15.0F;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
