package io.github.zgxhzhr.meowarchive.exporter.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * 效果图标：直接读模组资源里的 16×16 原图，按规则放大。禁止把 16×16 平滑放大。
 */
public final class EffectIconRenderer {
    private EffectIconRenderer() {
    }

    public static BufferedImage render(ResourceLocation id, int size) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(), "textures/mob_effect/" + id.getPath() + ".png");
        try {
            var resource = mc.getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().open()) {
                BufferedImage source = ImageIO.read(in);
                if (source == null) {
                    return null;
                }
                return ImageUtil.fit(source, size);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static int sourceColorCount(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(), "textures/mob_effect/" + id.getPath() + ".png");
        try {
            var resource = mc.getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return 0;
            }
            try (InputStream in = resource.get().open()) {
                BufferedImage source = ImageIO.read(in);
                return source == null ? 0 : ImageUtil.colorCount(source);
            }
        } catch (Throwable t) {
            return 0;
        }
    }
}
