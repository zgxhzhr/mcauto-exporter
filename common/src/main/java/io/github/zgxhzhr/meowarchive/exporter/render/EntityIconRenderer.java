package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

/**
 * 实体图标：客户端离屏渲染。
 * 每张只做一次 GL 绘制：多次重绘时上一轮的几何体会串进下一张纹理，表现为图标里出现重影。
 * 因此先画在一张放大一倍的画布上（保证翅膀、披风等超出碰撞箱的部分不被裁掉），
 * 再在纯 Java 侧裁出实际内容、缩放居中成目标尺寸。
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
            entity = type.create(mc.level);
        } catch (Throwable t) {
            return null;
        }
        if (entity == null) {
            return null;
        }
        try {
            entity.setPos(0.0, 0.0, 0.0);
            applyYaw(entity, resolveYaw(mc, entity));
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            entity.setNoGravity(true);
            entity.tickCount = 20;
            normalizeSpawnState(entity);

            // 只做一次 GL 绘制，画在放大一倍的画布上（翅膀、披风、尾巴超出碰撞箱也裁不到），
            // 再在纯 Java 侧裁出真实内容、缩放居中。多次重绘容易把上一轮内容带进下一张纹理。
            int canvas = size * 2;
            float bbH = Math.max(entity.getBbHeight(), 0.2F);
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            float fit = canvas * 0.9F;
            float scale = Math.min(fit / (bbH * 1.25F), fit / (bbW * 2.6F));

            // y 向上：把模型脚底放在画布底部附近，身体向上生长，保证整只实体都在画布内。
            BufferedImage raw = draw(entity, canvas, canvas / 2.0F, canvas * 0.06F, scale);
            if (raw == null) {
                return null;
            }
            return frame(raw, size);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 模型朝向：原版（LivingEntityRenderer）模型按 +Z 建模，30° 得到三分之四正面；
     * GeckoLib 等自定义渲染器的模型按 -Z 建模，同一变换下 30° 正好转到正侧面，
     * 取 15° 才能让脸朝向镜头（此前对它们沿用 150°，导致整体背对镜头）。
     */
    private static float resolveYaw(Minecraft mc, Entity entity) {
        return mc.getEntityRenderDispatcher().getRenderer(entity) instanceof LivingEntityRenderer
                ? 30.0F : 15.0F;
    }

    /** 统一设置实体的朝向（身体、头、历史角度一起改，避免插值产生偏转）。 */
    private static void applyYaw(Entity entity, float yaw) {
        // 1.16.5 没有旋转 setter，直接写公开字段；历史角度一起改，避免插值产生偏转。
        entity.yRot = yaw;
        entity.yRotO = yaw;
        entity.xRot = 0.0F;
        entity.xRotO = 0.0F;
        if (entity instanceof net.minecraft.world.entity.LivingEntity) {
            net.minecraft.world.entity.LivingEntity living =
                    (net.minecraft.world.entity.LivingEntity) entity;
            living.yBodyRot = yaw;
            living.yBodyRotO = yaw;
            living.yHeadRot = yaw;
            living.yHeadRotO = yaw;
        }
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
     * 排除画面里与主体无关的孤立碎片（例如一起被冲进来的 HUD 元素）。
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
            // 包围盒 + 面积权重：用包围盒面积近似排序
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

    private static BufferedImage draw(Entity entity, int size, float tx, float ty, float scale) {
        Minecraft mc = Minecraft.getInstance();
        OffscreenTarget target = null;
        MultiBufferSource.BufferSource buffers = null;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            // 实体渲染按左下原点建模，这里用 y 向上的投影，读取像素时再做上下翻转。
            ItemIconRenderer.setProjection(size, false);

            // 独立缓冲区：共享的 GUI 缓冲区里还有待提交的 HUD 顶点，
            // 一起冲进来会在图标边角留下碎片。
            buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.translate(tx, ty, 0.0F);
            // 投影已经是 y 向上，读取像素时又做了一次行翻转，这里不能再翻 y，否则图标上下颠倒。
            pose.scale(scale, scale, scale);
            mc.getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0, entity.yRot, 0.0F, pose,
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
            if (target != null) {
                target.close();
            }
            Minecraft main = Minecraft.getInstance();
            main.getMainRenderTarget().bindWrite(true);
            ItemIconRenderer.restoreProjection(main);
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
