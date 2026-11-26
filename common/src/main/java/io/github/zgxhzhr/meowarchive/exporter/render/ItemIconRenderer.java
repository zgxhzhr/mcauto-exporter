package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 物品 / 方块物品图标：离屏渲染，直接复用原版 GuiGraphics.renderItem 管线。
 *
 * <p>不能自己调 ItemStackRenderState.render：1.21.5 里物品贴图由 GUI 绘制路径绑定，
 * 单独调渲染状态会导致采样贴图为空（cutout 全部被丢弃，截出全透明图）。
 */
public final class ItemIconRenderer {
    /**
     * 3D 方块改用统一平面打光后不再偏暗，仅再补一点亮度，使方块图标与其它版本归档观感一致。
     * 平面物品（diamond/apple 等）已经是 1.0 倍，不受影响。
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
        boolean gui3d = isGui3d(mc, stack);
        try {
            IconCapture.renderAsync(size, graphics -> {
                PoseStack pose = graphics.pose();
                pose.pushPose();
                // 原版按 16×16 槽位绘制，外面整体放大到目标尺寸
                pose.scale(size / 16.0F, size / 16.0F, 1.0F);
                // GuiGraphics.renderItem 只在平面物品时切光照，3D 方块会沿用当前状态：
                // 先切平面打光，避免 3D 模型用 ITEMS_3D 的 0.4 环境光而整体偏暗。
                Lighting.setupForFlatItems();
                graphics.renderItem(stack, 0, 0);
                pose.popPose();
            }, image -> {
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

    /** 方块物品（GUI 里按 3D 光照绘制）才需要补亮度。 */
    private static boolean isGui3d(Minecraft mc, ItemStack stack) {
        try {
            ItemStackRenderState state = new ItemStackRenderState();
            mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
            return !state.isEmpty() && state.usesBlockLight();
        } catch (Throwable t) {
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
