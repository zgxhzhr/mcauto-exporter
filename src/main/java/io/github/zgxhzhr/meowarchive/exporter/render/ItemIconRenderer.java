package io.github.zgxhzhr.meowarchive.exporter.render;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;

/**
 * 物品 / 方块物品图标：GUI 阶段离屏渲染，走原版物品栏管线。
 */
public final class ItemIconRenderer {
    /**
     * 3D 方块改用平面打光后不再偏暗，仅再补一点亮度，
     * 使方块图标与原版观感及其它版本归档一致；平面物品不受影响。
     */
    private static final float BLOCK_GAIN = 1.3F;

    private ItemIconRenderer() {
    }

    public static BufferedImage render(ItemStack stack, int size) {
        Minecraft mc = Minecraft.getMinecraft();
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        OffscreenTarget target = null;
        try {
            target = new OffscreenTarget(size);
            target.begin();

            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            // 不用 GUI 标准打光（带 -30/165 的旋转，3D 方块只有 0.4~0.5 的亮度），
            // 换成不带旋转的标准物品打光；平面物品本来就不参与打光，亮度不受影响。
            RenderHelper.enableStandardItemLighting();

            GL11.glPushMatrix();
            GL11.glScalef(size / 16.0F, size / 16.0F, 1.0F);
            // 不用 renderItemAndEffectIntoGUI：它会给附魔物品叠一层 glint，
            // 而 glint 的混合模式会把整块区域的目标 alpha 清零，离屏读回来就是全透明。
            // 静态图标只需要物品本体，跳过 glint 也更稳定。
            mc.getRenderItem().renderItemIntoGUI(stack, 0, 0);
            GL11.glPopMatrix();

            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            BufferedImage image = target.read();
            if (image != null && rendersIn3D(stack)) {
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

    /** 3D 方块模型（isGui3d）在图标里仍需补亮度，平面物品保持原样。 */
    private static boolean rendersIn3D(ItemStack stack) {
        try {
            return Minecraft.getMinecraft().getRenderItem().shouldRenderItemIn3D(stack);
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
}
