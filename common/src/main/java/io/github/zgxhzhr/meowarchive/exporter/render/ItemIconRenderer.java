package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
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
     * 3D 方块改用统一平面打光后不再偏暗，仅再补一点亮度，
     * 使方块图标与原版观感及其它版本归档一致；平面物品不受影响。
     */
    private static final float BLOCK_GAIN = 1.3F;

    private ItemIconRenderer() {
    }

    public static BufferedImage render(ItemStack stack, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (stack.isEmpty()) {
            return null;
        }
        OffscreenTarget target = null;
        boolean lightingPushed = false;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            // 1.14.4 还是固定管线：投影矩阵用 ortho 直接设置（左上原点，y 向下）。
            setProjection(size, true);

            // HUD 阶段残留的是 GUI 标准打光（3D 方块在 GUI 里只有 0.4~0.5 的亮度）；
            // 这里换成平面打光（标准物品光照，不带 GUI 旋转），
            // 效果对应其它版本的 Lighting.setupForFlatItems()。
            GlStateManager.pushLightingAttributes();
            lightingPushed = true;
            Lighting.turnOn();

            // 16×16 的 GUI 物品放大到整张画布。物品栏管线的坐标原点是左上角，
            // 与这里的 y 向下投影一致；读取像素时再做一次上下翻转即可。
            GlStateManager.pushMatrix();
            GlStateManager.scalef(size / 16.0F, size / 16.0F, 1.0F);
            mc.getItemRenderer().renderGuiItem(stack, 0, 0);
            GlStateManager.popMatrix();

            Lighting.turnOff();
            GlStateManager.popAttributes();
            lightingPushed = false;

            BufferedImage image = target.read();
            if (image != null && isGui3d(mc, stack)) {
                brighten(image, BLOCK_GAIN);
            }
            return image;
        } catch (Throwable t) {
            ExporterLog.LOG.error("物品渲染异常: " + stack.getItem(), t);
            return null;
        } finally {
            if (lightingPushed) {
                Lighting.turnOff();
                GlStateManager.popAttributes();
            }
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
