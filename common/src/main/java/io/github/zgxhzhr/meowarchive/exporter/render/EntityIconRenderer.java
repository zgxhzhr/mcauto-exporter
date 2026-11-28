package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

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
        try {
            entity.setPos(0.0, 0.0, 0.0);
            float yaw = resolveYaw(entity);
            entity.setYRot(yaw);
            entity.setXRot(0.0F);
            entity.setYHeadRot(yaw);
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = yaw;
                living.yBodyRotO = yaw;
            }
            entity.setOldPosAndRot();
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            entity.setNoGravity(true);
            entity.tickCount = 20;
            normalizeSpawnState(entity);

            int canvas = size * 2;
            float bbH = Math.max(entity.getBbHeight(), 0.2F);
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            float fit = canvas * 0.9F;
            float scale = Math.min(fit / (bbH * 1.25F), fit / (bbW * 2.6F));

            mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            IconCapture.renderAsync(canvas, scale, scale, true, false, canvas / 2.0F, canvas * 0.94F,
                    (pose, buffers) -> mc.getEntityRenderDispatcher().render(
                            entity, 0.0, 0.0, 0.0, 0.0F, pose, buffers, 0xF000F0),
                    raw -> {
                        if (raw == null || ImageUtil.blank(raw)) {
                            done.accept(null);
                        } else {
                            done.accept(frame(raw, size));
                        }
                    });
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("实体渲染异常 " + type, t);
            done.accept(null);
        }
    }

    /** 用指定角度渲染实体类型，结果通过回调交付（渲染本身是异步的）。 */
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
            // 活体渲染会读取实体 id 做随机种子，未加入世界时 id 为 -1 会抛异常
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
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            float bbH = Math.max(entity.getBbHeight(), 0.2F);

            // 先按包围盒把模型缩到画布一半（留出余量给比包围盒更大的模型），
            // 再由 ImageUtil 按像素外廓裁剪、等比放到目标尺寸
            int canvas = Math.max(size * 2, 128);
            float scale = canvas * 0.5F / Math.max(bbW, bbH);
            float center = canvas / 2.0F;
            float modelHeight = bbH;

            mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            IconCapture.renderAsync(canvas, scale, scale, true, false, center, center,
                    (pose, buffers) -> {
                        // 模型原点在脚底、身体向上延伸，先下移半个身高让内容落在画布内
                        pose.translate(0.0F, -modelHeight / 2.0F, 0.0F);
                        if (options.pitch != 0.0F) {
                            pose.mulPose(new org.joml.Matrix4f()
                                    .rotationX((float) Math.toRadians(options.pitch)));
                        }
                        dispatcher.render(entity, 0.0, 0.0, 0.0, 0.0F, pose, buffers, FULL_BRIGHT);
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

    /** 裁出实际内容并缩放到目标尺寸：水平居中、底边对齐。 */
    private static BufferedImage frame(BufferedImage raw, int size) {
        int[] box = contentBox(raw);
        if (box == null) {
            return null;
        }
        int x0 = box[0];
        int y0 = box[1];
        int w = box[2] - box[0] + 1;
        int h = box[3] - box[1] + 1;
        float k = Math.min(size * 0.88F / w, size * 0.88F / h);
        float dw = w * k;
        float dh = h * k;
        float dx = (size - dw) / 2.0F;
        float dy = size * 0.94F - dh;

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        // 放大小图用最近邻保持像素风，缩小时用双线性避免锯齿
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                k >= 1.0F ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(raw, Math.round(dx), Math.round(dy), Math.round(dx + dw), Math.round(dy + dh),
                x0, y0, x0 + w, y0 + h, null);
        g.dispose();
        return out;
    }

    /**
     * 内容边界：以最大连通块为主体，并入与主体包围盒相接的其它块（翅膀分段、天线等），
     * 排除画面里与主体无关的孤立碎片。
     */
    private static int[] contentBox(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        boolean[] mask = new boolean[w * h];
        boolean anyMask = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    mask[y * w + x] = true;
                    anyMask = true;
                }
            }
        }
        if (!anyMask) {
            return null;
        }
        int[] label = new int[w * h];
        int[][] boxes = new int[4096][];
        int count = 0;
        int[] stack = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            if (!mask[i] || label[i] != 0) {
                continue;
            }
            int id = ++count;
            if (id >= boxes.length) {
                break;
            }
            int top = 0;
            stack[top++] = i;
            label[i] = id;
            int x0 = w;
            int y0 = h;
            int x1 = -1;
            int y1 = -1;
            while (top > 0) {
                int p = stack[--top];
                int px = p % w;
                int py = p / w;
                if (px < x0) {
                    x0 = px;
                }
                if (px > x1) {
                    x1 = px;
                }
                if (py < y0) {
                    y0 = py;
                }
                if (py > y1) {
                    y1 = py;
                }
                if (px > 0) {
                    top = push(mask, label, stack, top, p - 1, id);
                }
                if (px + 1 < w) {
                    top = push(mask, label, stack, top, p + 1, id);
                }
                if (py > 0) {
                    top = push(mask, label, stack, top, p - w, id);
                }
                if (py + 1 < h) {
                    top = push(mask, label, stack, top, p + w, id);
                }
            }
            boxes[id] = new int[]{x0, y0, x1, y1, (x1 - x0 + 1) * (y1 - y0 + 1)};
        }
        if (count == 0) {
            return null;
        }
        int main = 1;
        for (int id = 2; id <= count && id < boxes.length && boxes[id] != null; id++) {
            if (boxes[id][4] > boxes[main][4]) {
                main = id;
            }
        }
        int[] result = {boxes[main][0], boxes[main][1], boxes[main][2], boxes[main][3]};
        // 并入与主体包围盒相接（含 4px 容差）的其它块
        for (int id = 1; id <= count && id < boxes.length; id++) {
            int[] b = boxes[id];
            if (b == null || id == main) {
                continue;
            }
            boolean near = b[0] <= result[2] + 4 && b[2] >= result[0] - 4
                    && b[1] <= result[3] + 4 && b[3] >= result[1] - 4;
            if (near) {
                result[0] = Math.min(result[0], b[0]);
                result[1] = Math.min(result[1], b[1]);
                result[2] = Math.max(result[2], b[2]);
                result[3] = Math.max(result[3], b[3]);
            }
        }
        return result;
    }

    private static int push(boolean[] mask, int[] label, int[] stack, int top, int p, int id) {
        if (mask[p] && label[p] == 0) {
            label[p] = id;
            stack[top++] = p;
        }
        return top;
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

    /**
     * 模型朝向：原版（LivingEntityRenderer）模型按 +Z 建模，取 330° 得到三分之四正面；
     * GeckoLib 等自定义渲染器的模型按 -Z 建模，同一变换下 330° 正好转到正侧面，
     * 取 15° 才能让脸朝向镜头。
     */
    private static float resolveYaw(Entity entity) {
        try {
            if (Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity)
                    instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?, ?>) {
                return 330.0F;
            }
        } catch (Throwable ignored) {
        }
        return 15.0F;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
