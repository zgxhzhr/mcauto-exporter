package io.github.zgxhzhr.mcauto.exporter.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

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
    public final EntityType<?> entityType;
    public final MobEffect effect;
    public final Map<Integer, BufferedImage> icons = new LinkedHashMap<>();
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

    private Target(Kind kind, ResourceLocation id, ItemStack stack, EntityType<?> entityType, MobEffect effect) {
        this.kind = kind;
        this.id = id;
        this.stack = stack;
        this.entityType = entityType;
        this.effect = effect;
    }

    public static Target item(ResourceLocation id, ItemStack stack) {
        return new Target(Kind.ITEM, id, stack, null, null);
    }

    public static Target entity(ResourceLocation id, EntityType<?> type) {
        return new Target(Kind.ENTITY, id, null, type, null);
    }

    public static Target effect(ResourceLocation id, MobEffect effect) {
        return new Target(Kind.EFFECT, id, null, null, effect);
    }

    public String path() {
        return id.getPath();
    }

    public String namespace() {
        return id.getNamespace();
    }
}
