package io.github.zgxhzhr.meowarchive.exporter.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * 离屏渲染后的状态复位：重新绑定主帧缓冲，并把投影、颜色、混合等恢复成 HUD 阶段的样子，
 * 否则后续 HUD 元素会画到错误的位置或带着上一轮的深度/纹理状态。
 */
final class RenderUtil {

    private RenderUtil() {
    }

    static void restoreGuiState() {
        Minecraft mc = Minecraft.getInstance();
        mc.getFramebuffer().bindFramebuffer(true);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, mc.mainWindow.getScaledWidth(), mc.mainWindow.getScaledHeight(), 0.0D,
                1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translatef(0.0F, 0.0F, -2000.0F);

        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableDepthTest();
        GlStateManager.bindTexture(0);
    }
}
