package io.github.zgxhzhr.meowarchive.exporter.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * 效果图标：1.7.10 的效果图标是 inventory.png 里的一张 18×18 精灵表，
 * 按 Potion#getStatusIconIndex 取格子，再用最近邻放大。禁止平滑放大。
 */
public final class EffectIconRenderer {
    private static final ResourceLocation INVENTORY =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");
    private static final int ICON = 18;
    private static final int GRID_Y = 198;

    private static BufferedImage sheet;
    private static boolean sheetTried;

    private EffectIconRenderer() {
    }

    public static BufferedImage render(Potion potion, int size) {
        BufferedImage crop = cropIcon(potion);
        return crop == null ? null : ImageUtil.fit(crop, size);
    }

    public static int sourceColorCount(Potion potion) {
        BufferedImage crop = cropIcon(potion);
        return crop == null ? 0 : ImageUtil.colorCount(crop);
    }

    private static BufferedImage cropIcon(Potion potion) {
        BufferedImage source = sheet();
        if (source == null || potion == null) {
            return null;
        }
        int index = Math.max(0, potion.getStatusIconIndex());
        int col = index % 8;
        int row = index / 8;
        return ImageUtil.crop(source, col * ICON, GRID_Y + row * ICON, ICON, ICON);
    }

    private static BufferedImage sheet() {
        if (sheetTried) {
            return sheet;
        }
        sheetTried = true;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            IResourceManager rm = mc.getResourceManager();
            IResource resource = rm.getResource(INVENTORY);
            if (resource == null) {
                return null;
            }
            try (java.io.InputStream in = resource.getInputStream()) {
                sheet = ImageIO.read(in);
            }
        } catch (Throwable ignored) {
            sheet = null;
        }
        return sheet;
    }
}
