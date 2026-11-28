package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

/**
 * 实体图标：客户端离屏渲染。
 * 每张只做一次 GL 绘制：多次重绘时上一轮的几何体会串进下一张纹理，表现为图标里出现重影。
 * 因此先画在一张放大一倍的画布上（保证翅膀、披风等超出碰撞箱的部分不被裁掉），
 * 再在纯 Java 侧裁出实际内容、缩放居中成目标尺寸。
 *
 * <p>批量导出走 {@link #render(EntityType, int)}（默认参数，行为与之前一致）；
 * 单实体预览/导出走 {@link #render(Entity, int, EntityRenderOptions)}，可调角度、俯仰、缩放、
 * 偏移、亮度、阴影与背景。
 */
public final class EntityIconRenderer {
    private EntityIconRenderer() {
    }

    public static BufferedImage render(EntityType<?> type, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        Entity entity;
        try {
            entity = type.create(mc.level, EntitySpawnReason.COMMAND);
        } catch (Throwable t) {
            return null;
        }
        if (entity == null) {
            return null;
        }
        return render(entity, size, new EntityRenderOptions());
    }

    /** 用指定参数渲染实体（同步，只能在渲染线程调用）。 */
    public static BufferedImage render(Entity entity, int size, EntityRenderOptions options) {
        if (entity == null || options == null || size <= 0) {
            return null;
        }
        try {
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

            // 只做一次 GL 绘制，画在放大一倍的画布上（翅膀、披风、尾巴超出碰撞箱也裁不到），
            // 再在纯 Java 侧裁出真实内容、缩放居中。多次重绘容易把上一轮内容带进下一张纹理。
            int canvas = Math.max(size * 2, 128);
            float bbH = Math.max(entity.getBbHeight(), 0.2F);
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            float scale = canvas * 0.5F / Math.max(bbW, bbH);
            float center = canvas / 2.0F;
            float modelHeight = bbH;

            BufferedImage raw = draw(entity, canvas, center, center, scale, modelHeight, options.pitch);
            if (raw == null) {
                return null;
            }
            return finish(raw, size, options);
        } catch (Throwable t) {
            return null;
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
            g.setComposite(AlphaComposite.Src);
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

    private static BufferedImage draw(Entity entity, int size, float tx, float ty, float scale,
                                      float modelHeight, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        OffscreenTarget target = null;
        ByteBufferBuilder builder = null;
        MultiBufferSource.BufferSource buffers = null;
        try {
            // 上一轮可能还有未提交的批次，先冲到当前主目标，避免串进本次离屏纹理
            mc.renderBuffers().bufferSource().endBatch();
            target = new OffscreenTarget(size);
            target.begin();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, 21000.0F),
                    ProjectionType.ORTHOGRAPHIC);

            // 独立缓冲区：共享的 GUI 缓冲区里还有快捷栏等待提交的顶点，
            // 一起冲进来会在图标边角留下 HUD 碎片。
            builder = new ByteBufferBuilder(1 << 18);
            buffers = MultiBufferSource.immediate(builder);
            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.translate(tx, ty, 0.0F);
            pose.scale(scale, -scale, scale);
            // 模型原点在脚底、身体向上延伸，先下移半个身高让内容落在画布内
            pose.translate(0.0F, -modelHeight / 2.0F, 0.0F);
            if (pitch != 0.0F) {
                pose.mulPose(Axis.XP.rotationDegrees(pitch));
            }
            mc.getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0, 0.0F, pose,
                    buffers, 0xF000F0);
            buffers.endBatch();
            pose.popPose();

            return target.read();
        } catch (Throwable t) {
            return null;
        } finally {
            if (buffers != null) {
                try {
                    buffers.endBatch();
                } catch (Throwable ignored) {
                }
            }
            if (builder != null) {
                builder.close();
            }
            if (target != null) {
                target.close();
            }
            Minecraft main = Minecraft.getInstance();
            main.getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F,
                            main.getWindow().getGuiScaledWidth(),
                            main.getWindow().getGuiScaledHeight(),
                            0.0F, 1000.0F, 21000.0F),
                    ProjectionType.ORTHOGRAPHIC);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
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
