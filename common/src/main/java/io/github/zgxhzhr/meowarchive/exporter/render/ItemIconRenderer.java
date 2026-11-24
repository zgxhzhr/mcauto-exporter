package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.GlStateManager;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;

/**
 * 物品 / 方块物品图标：GUI 阶段离屏渲染，走原版物品栏管线。
 */
public final class ItemIconRenderer {
    /**
     * 1.14/1.15/1.16 的固定管线方块模型在离屏渲染时拿不到 GUI 物品光照，
     * 成品整体只有贴图亮度的约 0.43 倍（平面物品不受影响）。
     * 这里按实测比例补回亮度，使方块图标与原版观感及其它版本归档一致。
     * 1.14.4 的基准再低约 8%（固定管线没有 GUI 方向光），系数相应从 1.8 提到 1.95。
     */
    private static final float BLOCK_GAIN = 1.95F;

    private ItemIconRenderer() {
    }

    public static BufferedImage render(ItemStack stack, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (stack.isEmpty()) {
            return null;
        }
        OffscreenTarget target = null;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            // 1.14.4 还是固定管线：投影矩阵用 ortho 直接设置（左上原点，y 向下）。
            setProjection(size, true);

            // 16×16 的 GUI 物品放大到整张画布。物品栏管线的坐标原点是左上角，
            // 与这里的 y 向下投影一致；读取像素时再做一次上下翻转即可。
            GlStateManager.pushMatrix();
            GlStateManager.scalef(size / 16.0F, size / 16.0F, 1.0F);
            mc.getItemRenderer().renderGuiItem(stack, 0, 0);
            GlStateManager.popMatrix();

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
            restoreProjection(main);
            GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static boolean isGui3d(Minecraft mc, ItemStack stack) {
        try {
            return mc.getItemRenderer().isGui3d(stack);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 线性提亮，保留 alpha 与原图的明暗比例。 */
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

    /** yDown=true 时原点在左上角（GUI 习惯），false 时左下角（实体渲染习惯）。 */
    static void setProjection(int size, boolean yDown) {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        if (yDown) {
            GlStateManager.ortho(0.0, size, size, 0.0, 1000.0, 3000.0);
        } else {
            GlStateManager.ortho(0.0, size, 0.0, size, 1000.0, 3000.0);
        }
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        // 原版 GUI 的模型视图带一个 -2000 的 Z 平移，物品/实体都画在 z≈0..100，
        // 叠上平移后正好落进 ortho 的 1000~3000 深度范围；少了这一步所有几何都会被近平面裁掉。
        GlStateManager.translatef(0.0F, 0.0F, -2000.0F);
    }

    /** 还原 GUI 阶段的标准投影，避免影响后续 HUD 绘制。 */
    static void restoreProjection(Minecraft mc) {
        int width = mc.window.getGuiScaledWidth();
        int height = mc.window.getGuiScaledHeight();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, width, height, 0.0, 1000.0, 3000.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translatef(0.0F, 0.0F, -2000.0F);
    }
}
