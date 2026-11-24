package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Matrix4f;

import java.awt.image.BufferedImage;

/**
 * 物品 / 方块物品图标：GUI 阶段离屏渲染，走原版物品栏管线。
 */
public final class ItemIconRenderer {
    /**
     * 1.17 的 3D 方块走物品栏光照，实测比 1.15.2 归档暗约 17%；
     * 原石 / 铁块 / 羊毛乘 1.2 后与 1.15.2 归档对齐（97.7 / 171.8 / 184.2 vs 98.2 / 170.8 / 182.2）。
     */
    private static final float BLOCK_GAIN = 1.2F;

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
                    Matrix4f.orthographic(0.0F, size, 0.0F, size, 1000.0F, 3000.0F));

            // 1.19.2 的 renderGuiItem 会自己平移 / 缩放并提交批次，
            // 这里在模型视图栈上先放大到目标尺寸，物品中心落在画布中央。
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            modelView.scale(size / 16.0F, size / 16.0F, 1.0F);
            RenderSystem.applyModelViewMatrix();
            mc.getItemRenderer().renderGuiItem(stack, 0, 0);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();

            BufferedImage image = target.read();
            if (image != null && isGui3d(mc, stack)) {
                brighten(image, BLOCK_GAIN);
            }
            return image;
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

    private static boolean isGui3d(Minecraft mc, ItemStack stack) {
        try {
            return mc.getItemRenderer().getModel(stack, mc.level, null, 0).isGui3d();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 线性提亮，保留 alpha 与原图明暗比例。 */
    private static void brighten(BufferedImage image, float gain) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int a = argb >>> 24;
                if (a == 0) {
                    continue;
                }
                int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * gain));
                int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * gain));
                int b = Math.min(255, Math.round((argb & 0xFF) * gain));
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }
}
