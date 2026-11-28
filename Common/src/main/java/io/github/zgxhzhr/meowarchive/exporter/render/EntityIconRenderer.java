package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Axis;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.joml.Matrix4f;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

/**
 * 实体图标：客户端离屏渲染，默认 128×128。
 * 先按碰撞箱估一个比例渲染，再按实际像素范围重新缩放并居中，避免大体型实体被裁切。
 *
 * <p>批量导出走 {@link #render(EntityType, int)}（默认参数，行为与之前一致）；
 * 单实体预览/导出走 {@link #render(Entity, int, EntityRenderOptions)}，可调角度、俯仰、缩放、
 * 偏移、亮度、阴影与背景。渲染前后会对实体的位置/朝向/帧计数做快照与还原，可安全传入世界里的实体。
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
        return render(entity, size, new EntityRenderOptions());
    }

    /**
     * 用指定参数渲染实体。传入世界实体时不会改变其可见状态（内部做快照还原）。
     */
    public static BufferedImage render(Entity entity, int size, EntityRenderOptions options) {
        if (entity == null || options == null || size <= 0) {
            return null;
        }
        Snapshot snapshot = Snapshot.of(entity);
        try {
            float yaw = options.yaw == null ? resolveYaw(entity) : options.yaw;
            prepare(entity, yaw, options);

            float bbH = Math.max(entity.getBbHeight(), 0.2F);
            float bbW = Math.max(entity.getBbWidth(), 0.2F);
            float margin = clamp(options.margin, 0.4F, 1.0F);
            float fit = size * margin;
            float scale = Math.min(fit / bbH, fit / (bbW * 2.2F));
            float maxScale = size * 1.2F;

            float tx = size / 2.0F;
            float ty = size * 0.94F;
            BufferedImage image;
            if (options.autoFit) {
                // 先在更大的画布上测出模型实际占据的像素范围，再一次性换算比例与位置。
                // 不依赖碰撞箱宽度估比例，也不假设“模型底边=原点”：部分渲染器会把模型画在原点下方，
                // 旧版用画布内迭代反馈时会把这种情况误判成溢出，反复缩小，最后缩成角落小块。
                float target = size * margin;
                int probeSize = Math.max(size * 2, 256);
                float probeScale = probeSize * 0.7F / Math.max(Math.max(bbH, bbW), 0.2F);
                int[] box = null;
                boolean clipped = false;
                for (int attempt = 0; attempt < 3; attempt++) {
                    BufferedImage probe = draw(entity, probeSize, yaw, options.pitch,
                            probeSize / 2.0F, probeSize / 2.0F, probeScale, options.brightness);
                    if (probe == null) {
                        return null;
                    }
                    box = robustBounds(probe);
                    if (box == null) {
                        return null;
                    }
                    clipped = box[0] <= 0 || box[1] <= 0
                            || box[2] >= probeSize - 1 || box[3] >= probeSize - 1;
                    if (!clipped) {
                        break;
                    }
                    probeScale *= 0.5F;
                }
                float contentW = box[2] - box[0] + 1;
                float contentH = box[3] - box[1] + 1;
                float scaleRatio = Math.min(Math.min(target / contentW, target / contentH),
                        maxScale / probeScale);
                scale = probeScale * scaleRatio;
                tx = size / 2.0F - ((box[0] + box[2]) / 2.0F - probeSize / 2.0F) * scaleRatio;
                ty = size * 0.94F - (box[3] - probeSize / 2.0F) * scaleRatio;
                image = draw(entity, size, yaw, options.pitch, tx, ty, scale, options.brightness);
                if (image == null) {
                    return null;
                }
                if (clipped) {
                    ExporterLog.LOG.warn("自动构图仍触边 type={} size={} probe={} box={} scale={}",
                            entity.getType(), size, probeSize, java.util.Arrays.toString(box), scale);
                }
            } else {
                image = draw(entity, size, yaw, options.pitch, tx, ty, scale, options.brightness);
                if (image == null) {
                    return null;
                }
            }

            // 用户缩放/偏移：绕画布中心缩放，再整体平移
            if (options.zoom != 1.0F || options.offsetX != 0.0F || options.offsetY != 0.0F) {
                float zx = size / 2.0F + (tx - size / 2.0F) * options.zoom + options.offsetX * size;
                float zy = size / 2.0F + (ty - size / 2.0F) * options.zoom - options.offsetY * size;
                float zs = scale * options.zoom;
                BufferedImage zoomed = draw(entity, size, yaw, options.pitch, zx, zy, zs, options.brightness);
                if (zoomed != null) {
                    image = zoomed;
                    tx = zx;
                    ty = zy;
                }
            }

            return finish(image, size, options, tx, ty);
        } catch (Throwable t) {
            return null;
        } finally {
            snapshot.restore(entity);
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
     * 模型朝向：原版（LivingEntityRenderer）模型按 +Z 建模，30° 得到三分之四正面；
     * GeckoLib 等自定义渲染器的模型按 -Z 建模，同一变换下 30° 正好转到正侧面，
     * 取 15° 才能让脸朝向镜头（此前对它们沿用调用方的 150°，导致整体背对镜头）。
     */
    private static float resolveYaw(Entity entity) {
        try {
            if (Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity)
                    instanceof LivingEntityRenderer<?, ?>) {
                return 30.0F;
            }
        } catch (Throwable ignored) {
        }
        return 15.0F;
    }

    private static void prepare(Entity entity, float yaw, EntityRenderOptions options) {
        entity.setPos(0.0, 0.0, 0.0);
        entity.setYRot(yaw);
        entity.setXRot(0.0F);
        float headYaw = options.headYaw == null ? yaw : options.headYaw;
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
    }

    private static BufferedImage draw(Entity entity, int size, float yaw, float pitch,
                                      float tx, float ty, float scale, float brightness) {
        Minecraft mc = Minecraft.getInstance();
        OffscreenTarget target = null;
        MultiBufferSource.BufferSource buffers = null;
        Matrix4f previousProjection = null;
        try {
            // 上一轮可能还有未提交的批次，先冲到当前主目标，避免串进本次离屏纹理
            mc.renderBuffers().bufferSource().endBatch();
            previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            target = new OffscreenTarget(size);
            target.begin();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            float b = clamp(brightness, 0.1F, 2.0F);
            RenderSystem.setShaderColor(b, b, b, 1.0F);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, 3000.0F));

            // 独立缓冲区：共享的 GUI 缓冲区里还有待提交的 HUD 顶点，
            // 一起冲进来会在图标边角留下碎片。
            buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.translate(tx, ty, 0.0F);
            pose.scale(scale, -scale, scale);
            // 末影龙的渲染器完全无视传入的 yaw，而是按自身的飞行轨迹数组决定朝向；
            // 裸建实体没有轨迹数据，水平角度就永远不变，这里自己绕 Y 轴转。
            if (entity instanceof EnderDragon) {
                pose.mulPose(Axis.YP.rotationDegrees(yaw));
            }
            if (pitch != 0.0F) {
                pose.mulPose(Axis.XP.rotationDegrees(pitch));
            }
            mc.getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0, yaw, 0.0F, pose,
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
            if (previousProjection != null) {
                RenderSystem.setProjectionMatrix(previousProjection);
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 背景与阴影在 Java2D 侧合成，避免在 3D 离屏里再搭一套 GUI 渲染。 */
    private static BufferedImage finish(BufferedImage image, int size, EntityRenderOptions options,
                                        float tx, float ty) {
        if (options.transparent && !options.shadow) {
            return image;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            if (!options.transparent) {
                g.setColor(new Color(options.background, true));
                g.fillRect(0, 0, size, size);
            }
            if (options.shadow) {
                int[] box = bounds(image);
                int contentW = box == null ? (int) (size * 0.4F) : box[2] - box[0] + 1;
                int w = Math.max(6, (int) (contentW * 0.9F));
                int h = Math.max(3, (int) (w * 0.22F));
                int cx = box == null ? (int) tx : (box[0] + box[2]) / 2;
                int cy = (int) ty - h / 2;
                g.setColor(new Color(0, 0, 0, 72));
                g.fillOval(cx - w / 2, cy - h / 2, w, h);
            }
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
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

    /**
     * 自动构图用的稳健边界：忽略过淡的像素，以及只占极少像素的行/列。
     * 部分模组渲染器会在画布边缘画很淡的光晕/特效面片，普通 bounds 会把它当成主体，
     * 导致内容被反复缩小、最后缩成一小块。
     */
    private static int[] robustBounds(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] cols = new int[w];
        int[] rows = new int[h];
        int maxCol = 0;
        int maxRow = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((image.getRGB(x, y) >>> 24) > 24) {
                    cols[x]++;
                    rows[y]++;
                }
            }
        }
        for (int v : cols) {
            maxCol = Math.max(maxCol, v);
        }
        for (int v : rows) {
            maxRow = Math.max(maxRow, v);
        }
        if (maxCol == 0 || maxRow == 0) {
            return bounds(image);
        }
        int colMin = Math.max(2, (int) (maxCol * 0.02F));
        int rowMin = Math.max(2, (int) (maxRow * 0.02F));
        int x0 = w;
        int y0 = h;
        int x1 = -1;
        int y1 = -1;
        for (int x = 0; x < w; x++) {
            if (cols[x] >= colMin) {
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
            }
        }
        for (int y = 0; y < h; y++) {
            if (rows[y] >= rowMin) {
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
        }
        return x1 < 0 || y1 < 0 ? bounds(image) : new int[]{x0, y0, x1, y1};
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 渲染前的实体状态快照，渲染后还原，避免影响世界里正在展示的实体。 */
    private static final class Snapshot {
        private final double x;
        private final double y;
        private final double z;
        private final float yRot;
        private final float xRot;
        private final float yHeadRot;
        private final float yHeadRotO;
        private final float yBodyRot;
        private final float yBodyRotO;
        private final boolean noGravity;
        private final int tickCount;
        private final double dx;
        private final double dy;
        private final double dz;
        private final boolean living;

        private Snapshot(Entity entity) {
            this.x = entity.getX();
            this.y = entity.getY();
            this.z = entity.getZ();
            this.yRot = entity.getYRot();
            this.xRot = entity.getXRot();
            this.yHeadRot = entity.getYHeadRot();
            this.noGravity = entity.isNoGravity();
            this.tickCount = entity.tickCount;
            var movement = entity.getDeltaMovement();
            this.dx = movement.x;
            this.dy = movement.y;
            this.dz = movement.z;
            this.living = entity instanceof LivingEntity;
            if (entity instanceof LivingEntity living) {
                this.yHeadRotO = living.yHeadRotO;
                this.yBodyRot = living.yBodyRot;
                this.yBodyRotO = living.yBodyRotO;
            } else {
                this.yHeadRotO = 0.0F;
                this.yBodyRot = 0.0F;
                this.yBodyRotO = 0.0F;
            }
        }

        private static Snapshot of(Entity entity) {
            return new Snapshot(entity);
        }

        private void restore(Entity entity) {
            entity.setPos(x, y, z);
            entity.setYRot(yRot);
            entity.setXRot(xRot);
            entity.setYHeadRot(yHeadRot);
            entity.setDeltaMovement(dx, dy, dz);
            entity.setNoGravity(noGravity);
            entity.tickCount = tickCount;
            if (living && entity instanceof LivingEntity living) {
                living.yHeadRotO = yHeadRotO;
                living.yBodyRot = yBodyRot;
                living.yBodyRotO = yBodyRotO;
            }
            entity.setOldPosAndRot();
        }
    }
}
