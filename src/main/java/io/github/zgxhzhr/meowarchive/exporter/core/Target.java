package io.github.zgxhzhr.meowarchive.exporter.core;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Target {
    public enum Kind {
        ITEM("items"), ENTITY("entities"), EFFECT("effects");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    public final Kind kind;
    public final ResourceLocation id;
    public final ItemStack stack;
    public final Class<? extends Entity> entityClass;
    public final Potion effect;
    public final Map<Integer, BufferedImage> icons = new LinkedHashMap<>();
    /** 物品：平面动画模型导出的 GIF 字节，按图标尺寸存放。 */
    public final Map<Integer, byte[]> animatedGifs = new LinkedHashMap<>();
    public String nameZh = "";
    public String nameEn = "";
    public String creativeTab = "未知";
    public String tags = "[]";
    public String entityCategory = "";
    public double width;
    public double height;
    public double maxHealth;
    public boolean fireImmune;
    public boolean tameable;
    public String renderer = "";
    public boolean noVisual;
    public boolean suspect;
    public String suspectReason = "";
    public int color;
    public boolean instant;
    public String effectCategory = "";
    public int expectedColorCount;

    private Target(Kind kind, ResourceLocation id, ItemStack stack,
                   Class<? extends Entity> entityClass, Potion effect) {
        this.kind = kind;
        this.id = id;
        this.stack = stack;
        this.entityClass = entityClass;
        this.effect = effect;
    }

    public static Target item(ResourceLocation id, ItemStack stack) {
        return new Target(Kind.ITEM, id, stack, null, null);
    }

    public static Target entity(ResourceLocation id, Class<? extends Entity> entityClass) {
        return new Target(Kind.ENTITY, id, null, entityClass, null);
    }

    public static Target effect(ResourceLocation id, Potion effect) {
        return new Target(Kind.EFFECT, id, null, null, effect);
    }

    public String path() {
        return id.getResourcePath();
    }

    /** 日志与失败统计的唯一键：低版本元数据变体附在注册名后，避免同名条目互相覆盖。 */
    public String uid() {
        return stack != null && stack.getMetadata() != 0
                ? id.toString() + "#" + stack.getMetadata()
                : id.toString();
    }

    public String namespace() {
        return id.getResourceDomain();
    }
}
