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

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 实体图标：与背包界面的实体预览同一套变换（Z 轴 180° 旋转 + 按包围盒居中）。
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
        try {
            entity.setPos(0.0, 0.0, 0.0);
            // 26.2 的活体渲染会读取实体 id 做物品模型随机种子，未加入世界时 id 为 -1 会抛异常
            entity.setId(1);
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

            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            EntityRenderState state = dispatcher.extractEntity(entity, 0.0F);
            state.shadowPieces.clear();
            state.outlineColor = 0;
            state.lightCoords = FULL_BRIGHT;

            float bbW;
            float bbH;
            if (state instanceof LivingEntityRenderState living) {
                // yRot 是相对身体的角度（extractRenderState 里用 headRot - bodyRot 算），
                // 因此身体角度取 yaw、头部相对角度取 0，才能让脸朝向镜头
                living.bodyRot = yaw;
                living.yRot = 0.0F;
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
            // 再用像素外廓裁剪、等比放到目标尺寸，避免裁切和过小
            int canvas = Math.max(size * 2, 128);
            float scale = canvas * 0.5F / Math.max(bbW, bbH);
            float center = canvas / 2.0F;
            float modelHeight = bbH;

            CameraRenderState camera = new CameraRenderState();
            mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            IconCapture.renderAsync(canvas, scale, true, false, center, center,
                    (pose, collector) -> {
                        // 模型原点在脚底、身体向上延伸，先下移半个身高让内容落在画布内，避免头部被裁
                        pose.translate(0.0F, -modelHeight / 2.0F, 0.0F);
                        dispatcher.submit(state, camera, 0.0, 0.0, 0.0, pose, collector);
                    },
                    image -> {
                        if (image == null || ImageUtil.blank(image)) {
                            done.accept(null);
                            return;
                        }
                        BufferedImage fitted = ImageUtil.fitContent(image, size, 0.94F);
                        done.accept(fitted);
                    });
        } catch (Throwable t) {
            io.github.zgxhzhr.meowarchive.exporter.ExporterLog.LOG.error("实体渲染异常 " + type, t);
            done.accept(null);
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
}
