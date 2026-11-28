package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 实体图标：离屏渲染并裁剪到内容区域。
 *
 * <p>批量导出走 {@link #renderAsync(EntityType, int, float, Consumer)}（默认参数，行为与之前一致）；
 * 单实体预览/导出走 {@link #renderAsync(Entity, int, EntityRenderOptions, Consumer)}，可调角度、俯仰、
 * 缩放、偏移、亮度、阴影与背景。
 */
public final class EntityIconRenderer {
    private static final int FULL_BRIGHT = 0xF000F0;

    private EntityIconRenderer() {
    }

    public static void renderAsync(EntityType<?> type, int size, float yaw, Consumer<BufferedImage> done) {
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
        EntityRenderOptions options = new EntityRenderOptions();
        options.yaw = yaw;
        renderAsync(entity, size, options, done);
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
            float yaw = options.yaw == null ? 30.0F : options.yaw;
            float headYaw = options.headYaw == null ? yaw : options.headYaw;
            entity.setPos(0.0, 0.0, 0.0);
            // 26.2 的活体渲染会读取实体 id 做物品模型随机种子，未加入世界时 id 为 -1 会抛异常
            entity.setId(1);
            entity.setYRot(yaw);
            entity.setXRot(0.0F);
            entity.setYHeadRot(headYaw);
            if (entity instanceof LivingEntity living) {
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

            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            EntityRenderState state = dispatcher.extractEntity(entity, 0.0F);
            state.shadowPieces.clear();
            state.outlineColor = 0;
            state.lightCoords = FULL_BRIGHT;

            float bbW;
            float bbH;
            if (state instanceof LivingEntityRenderState living) {
                // yRot 是相对身体的角度（extractRenderState 里用 headRot - bodyRot 算）
                living.bodyRot = yaw;
                living.yRot = headYaw - yaw;
                living.xRot = 0.0F;
                living.boundingBoxWidth /= living.scale;
                living.boundingBoxHeight /= living.scale;
                living.scale = 1.0F;
                bbW = living.boundingBoxWidth;
                bbH = living.boundingBoxHeight;
            } else {
                bbW = state.boundingBoxWidth;
                bbH = state.boundingBoxHeight;
            }
            bbW = Math.max(bbW, 0.2F);
            bbH = Math.max(bbH, 0.2F);

            // 先按包围盒把模型缩到画布一半（留出余量给比包围盒更大的模型），
            // 再由 ImageUtil 按像素外廓裁剪、等比放到目标尺寸
            int canvas = Math.max(size * 2, 128);
            float scale = canvas * 0.5F / Math.max(bbW, bbH);
            float center = canvas / 2.0F;
            float modelHeight = bbH;

            CameraRenderState camera = new CameraRenderState();
            mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            IconCapture.renderAsync(canvas, scale, true, false, center, center,
                    (pose, collector) -> {
                        // 模型原点在脚底、身体向上延伸，先下移半个身高让内容落在画布内
                        pose.translate(0.0F, -modelHeight / 2.0F, 0.0F);
                        if (options.pitch != 0.0F) {
                            pose.mulPose(new org.joml.Matrix4f()
                                    .rotationX((float) Math.toRadians(options.pitch)));
                        }
                        // 末影龙渲染器按自身轨迹决定朝向，裸建实体轨迹为空，这里自己绕 Y 轴转
                        if (entity instanceof EnderDragon) {
                            pose.mulPose(new org.joml.Matrix4f()
                                    .rotationY((float) Math.toRadians(yaw)));
                        }
                        dispatcher.submit(state, camera, 0.0, 0.0, 0.0, pose, collector);
                    },
                    image -> {
                        if (image == null || ImageUtil.blank(image)) {
                            done.accept(null);
                            return;
                        }
                        done.accept(finish(image, size, options));
                    });
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("实体渲染异常 " + source.getType(), t);
            done.accept(null);
        }
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
            // 这里必须用默认的 SrcOver：Src 会把 fitted 的透明像素也写进画布，
            // 缩放后只覆盖中间一块，背景色就被“挖”成一个透明/黑色的方框。
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
