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
            RenderHelper.enableGUIStandardItemLighting();

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

            return target.read();
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
}
