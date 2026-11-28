package io.github.zgxhzhr.meowarchive.exporter.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把离屏渲染出的 BufferedImage 包成一张动态贴图，供界面直接 blit。
 *
 * <p>每次刷新都新建一张贴图并换用新的贴图 ID：DynamicTexture#setPixels 复用旧对象时，
 * 上传后的画面在部分情况下不会生效（预览停在第一张图），重建最稳。
 */
final class PreviewTexture {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private DynamicTexture texture;
    private ResourceLocation id;
    private int texSize;

    ResourceLocation id() {
        return id;
    }

    int size() {
        return texSize;
    }

    boolean ready() {
        return texture != null;
    }

    void update(BufferedImage image) {
        if (image == null) {
            return;
        }
        int size = image.getWidth();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                // NativeImage 的像素格式是 ABGR（小端 RGBA）
                nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        DynamicTexture next = new DynamicTexture(nativeImage);
        next.upload();
        ResourceLocation nextId = ResourceLocation.fromNamespaceAndPath("meowarchive",
                "dynamic/entity_preview_" + COUNTER.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(nextId, next);
        close();
        texture = next;
        id = nextId;
        texSize = size;
    }

    void close() {
        if (id != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            id = null;
        }
        texture = null;
        texSize = 0;
    }
}
