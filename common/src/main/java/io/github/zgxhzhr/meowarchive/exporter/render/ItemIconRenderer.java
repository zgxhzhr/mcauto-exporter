package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;

/**
 * 物品 / 方块物品图标：GUI 阶段离屏渲染，走原版物品栏管线。
 */
public final class ItemIconRenderer {
    private ItemIconRenderer() {
    }

    public static BufferedImage render(ItemStack stack, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (stack.isEmpty()) {
            return null;
        }
        OffscreenTarget target = null;
        MultiBufferSource.BufferSource buffers = null;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableAlphaTest();
            RenderSystem.defaultAlphaFunc();
            RenderSystem.enableRescaleNormal();
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            // 1.16.5 还是固定管线：投影矩阵用 ortho 直接设置（左上原点，y 向下）。
            setProjection(size, true);

            mc.getTextureManager().bind(TextureAtlas.LOCATION_BLOCKS);
            mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);

            // 16×16 的 GUI 物品放大到整张画布。物品栏管线的坐标原点是左上角，
            // 与这里的 y 向下投影一致；读取像素时再做一次上下翻转即可。
            PoseStack pose = new PoseStack();
            pose.scale(size / 16.0F, size / 16.0F, 1.0F);
            pose.translate(8.0, 8.0, 100.0);
            pose.scale(1.0F, -1.0F, 1.0F);
            pose.scale(16.0F, 16.0F, 16.0F);

            // 不走 renderGuiItem：它按模型的 usesBlockLight 决定打光，
            // 3D 方块在 GUI 里只有 0.4~0.5 的亮度，导出到透明背景上明显偏暗。
            // 统一改用平面打光（三面亮度接近），并给 3D 模型补一点亮度。
            BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player);
            Lighting.setupForFlatItems();
            RenderSystem.enableDepthTest();
            if (model.isGui3d()) {
                RenderSystem.color4f(1.3F, 1.3F, 1.3F, 1.0F);
            }
            buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            mc.getItemRenderer().render(stack, ItemTransforms.TransformType.GUI, false, pose,
                    buffers, 15728880, OverlayTexture.NO_OVERLAY, model);
            buffers.endBatch();
            buffers = null;
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableRescaleNormal();
            RenderSystem.disableAlphaTest();

            return target.read();
        } catch (Throwable t) {
            ExporterLog.LOG.error("物品渲染异常: " + stack.getItem(), t);
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
            restoreProjection(main);
            // 还原 GUI 阶段的物品打光，避免影响后续 HUD / 界面绘制
            Lighting.setupFor3DItems();
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** yDown=true 时原点在左上角（GUI 习惯），false 时左下角（实体渲染习惯）。 */
    static void setProjection(int size, boolean yDown) {
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.loadIdentity();
        if (yDown) {
            RenderSystem.ortho(0.0, size, size, 0.0, 1000.0, 3000.0);
        } else {
            RenderSystem.ortho(0.0, size, 0.0, size, 1000.0, 3000.0);
        }
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.loadIdentity();
        // 原版 GUI 的模型视图带一个 -2000 的 Z 平移，物品/实体都画在 z≈0..100，
        // 叠上平移后正好落进 ortho 的 1000~3000 深度范围；少了这一步所有几何都会被近平面裁掉。
        RenderSystem.translatef(0.0F, 0.0F, -2000.0F);
    }

    /** 还原 GUI 阶段的标准投影，避免影响后续 HUD 绘制。 */
    static void restoreProjection(Minecraft mc) {
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0, width, height, 0.0, 1000.0, 3000.0);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.loadIdentity();
        RenderSystem.translatef(0.0F, 0.0F, -2000.0F);
    }
}
