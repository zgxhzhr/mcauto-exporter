package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
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
                    new Matrix4f().setOrtho(0.0F, size, 0.0F, size, 1000.0F, 3000.0F));

            // 1.19.3 的 renderGuiItem 会自己平移 / 缩放并提交批次，
            // 这里在模型视图栈上先放大到目标尺寸，物品中心落在画布中央。
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            modelView.scale(size / 16.0F, size / 16.0F, 1.0F);
            RenderSystem.applyModelViewMatrix();
            mc.getItemRenderer().renderGuiItem(stack, 0, 0);
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
