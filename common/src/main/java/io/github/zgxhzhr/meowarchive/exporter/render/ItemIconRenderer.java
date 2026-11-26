package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 物品 / 方块物品图标：离屏渲染，直接复用原版物品模型管线。
 *
 * <p>1.21.8 的 GUI 物品也是按「物品图集」的姿态绘制：原点居中、Y 轴取负、1 模型单位 = 图标像素边长，
 * 这里保持同样的变换，保证与其它版本归档观感一致。
 */
public final class ItemIconRenderer {
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * 3D 方块改用统一平面打光后不再偏暗，仅再补一点亮度，保证导出图观感一致。
     */
    private static final float BLOCK_GAIN = 1.3F;

    private ItemIconRenderer() {
    }

    public static void renderAsync(ItemStack stack, int size, Consumer<BufferedImage> done) {
        Minecraft mc = Minecraft.getInstance();
        if (stack == null || stack.isEmpty() || mc.level == null) {
            done.accept(null);
            return;
        }
        try {
            ItemStackRenderState state = new ItemStackRenderState();
            mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
            if (state.isEmpty()) {
                done.accept(null);
                return;
            }
            boolean gui3d = state.usesBlockLight();
            // 3D 方块的 ITEMS_3D 打光只有 0.4~0.5 亮度，导出到透明背景上明显偏暗；统一用平面打光
            mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
            float center = size / 2.0F;
            IconCapture.renderAsync(size, size, 1.0F, true, false, center, center,
                    (pose, buffers) -> state.render(pose, buffers, FULL_BRIGHT, OverlayTexture.NO_OVERLAY),
                    image -> {
                        if (image == null || ImageUtil.blank(image)) {
                            done.accept(null);
                        } else {
                            if (gui3d) {
                                brighten(image, BLOCK_GAIN);
                            }
                            done.accept(image);
                        }
                    });
        } catch (Throwable t) {
            ExporterLog.LOG.error("物品渲染异常: " + stack.getItem(), t);
            done.accept(null);
        }
    }

    /** 线性提亮，保留 alpha 与原图的明暗比例。 */
    private static void brighten(BufferedImage image, float gain) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0) {
                    continue;
                }
                int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * gain));
                int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * gain));
                int b = Math.min(255, Math.round((argb & 0xFF) * gain));
                image.setRGB(x, y, (argb & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
    }
}
