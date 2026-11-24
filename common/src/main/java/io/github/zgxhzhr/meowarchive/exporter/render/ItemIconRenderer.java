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
 * 物品 / 方块物品图标：离屏渲染，走原版物品模型管线。
 */
public final class ItemIconRenderer {
    private static final int FULL_BRIGHT = 0xF000F0;

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
            boolean flat = !state.usesBlockLight();
            mc.gameRenderer.getLighting().setupFor(flat ? Lighting.Entry.ITEMS_FLAT : Lighting.Entry.ITEMS_3D);
            float center = size / 2.0F;
            IconCapture.renderAsync(size, size, true, false, center, center,
                    (pose, collector) -> state.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0),
                    image -> {
                        if (image == null || ImageUtil.blank(image)) {
                            done.accept(null);
                        } else {
                            done.accept(image);
                        }
                    });
        } catch (Throwable t) {
            ExporterLog.LOG.error("物品渲染异常: " + stack.getItem(), t);
            done.accept(null);
        }
    }
}
