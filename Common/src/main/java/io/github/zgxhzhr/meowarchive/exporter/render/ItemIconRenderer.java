package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

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
        Matrix4f previousProjection = null;
        try {
            // 上一轮可能还有未提交的批次，先冲到当前主目标，避免串进本次离屏纹理
            mc.renderBuffers().bufferSource().endBatch();
            previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            target = new OffscreenTarget(size);
            target.begin();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, size, size, 0.0F, 1000.0F, 3000.0F));

            // 1.19.2 的 renderGuiItem 会自己平移 / 缩放并提交批次，
            // 这里在模型视图栈上先放大到目标尺寸，物品中心落在画布中央。
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            modelView.scale(size / 16.0F, size / 16.0F, 1.0F);
            RenderSystem.applyModelViewMatrix();
            // 不走默认打光：renderGuiItem 按模型的 gui_light 决定打光，
            // 3D 方块在 GUI 里只有 0.4~0.5 的亮度，导出到透明背景上明显偏暗。
            // 统一改用平面打光（三面亮度接近），并给 3D 模型补一点亮度。
            Lighting.setupForFlatItems();
            BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
            if (model.isGui3d()) {
                RenderSystem.setShaderColor(1.3F, 1.3F, 1.3F, 1.0F);
            }
            mc.getItemRenderer().renderGuiItem(new PoseStack(), stack, 0, 0);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();

            return target.read();
        } catch (Throwable t) {
            ExporterLog.LOG.error("物品渲染异常: " + stack.getItem(), t);
            return null;
        } finally {
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
}
