package io.github.zgxhzhr.meowarchive.exporter.render;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;

/**
 * 物品 / 方块物品图标：GUI 阶段离屏渲染，走原版物品栏管线。
 */
public final class ItemIconRenderer {
    /**
     * GUI 标准打光带旋转，3D 方块模型在离屏渲染里会偏暗；
     * 统一改用平面打光后，再按其它版本的观感给 3D 模型补 1.3 倍亮度，平面物品不受影响。
     */
    private static final float BLOCK_GAIN = 1.3F;

    private ItemIconRenderer() {
    }

    public static BufferedImage render(ItemStack stack, int size) {
        Minecraft mc = Minecraft.getMinecraft();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        OffscreenTarget target = null;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            GlStateManager.enableRescaleNormal();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.enableDepth();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            // 平面打光（不带 GUI 旋转），对应其它版本的平面物品光照
            RenderHelper.enableStandardItemLighting();

            GlStateManager.pushMatrix();
            GlStateManager.scale(size / 16.0F, size / 16.0F, 1.0F);
            mc.getRenderItem().renderItemIntoGUI(stack, 0, 0);
            GlStateManager.popMatrix();

            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableBlend();
            GlStateManager.disableDepth();

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
            RenderUtil.restoreGuiState();
        }
    }

    private static boolean isGui3d(Minecraft mc, ItemStack stack) {
        try {
            return mc.getRenderItem().shouldRenderItemIn3D(stack);
        } catch (Throwable e) {
            return stack.getItem() instanceof net.minecraft.item.ItemBlock;
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
}
