package io.github.zgxhzhr.meowarchive.exporter.render;

import net.minecraft.client.Minecraft;
import net.minecraft.potion.Potion;
import net.minecraft.resources.IResource;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 效果图标：1.13 起每种效果有独立贴图 textures/mob_effect/&lt;注册名&gt;.png，
 * 直接读贴图后再用最近邻放大。禁止平滑放大。
 */
public final class EffectIconRenderer {
    private static final Map<ResourceLocation, BufferedImage> CACHE = new HashMap<>();

    private EffectIconRenderer() {
    }

    public static BufferedImage render(Potion potion, int size) {
        BufferedImage crop = texture(potion);
        return crop == null ? null : ImageUtil.fit(crop, size);
    }

    public static int sourceColorCount(Potion potion) {
        BufferedImage crop = texture(potion);
        return crop == null ? 0 : ImageUtil.colorCount(crop);
    }

    private static BufferedImage texture(Potion potion) {
        if (potion == null || potion.getRegistryName() == null) {
            return null;
        }
        ResourceLocation id = potion.getRegistryName();
        BufferedImage cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        ResourceLocation location =
                new ResourceLocation(id.getNamespace(), "textures/mob_effect/" + id.getPath() + ".png");
        BufferedImage image = null;
        try (IResource resource = Minecraft.getInstance().getResourceManager().getResource(location)) {
            if (resource != null) {
                image = ImageIO.read(resource.getInputStream());
            }
        } catch (Throwable ignored) {
            image = null;
        }
        CACHE.put(id, image);
        return image;
    }
}
