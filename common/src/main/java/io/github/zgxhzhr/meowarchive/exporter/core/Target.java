package io.github.zgxhzhr.meowarchive.exporter.core;

import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Target {
    public enum Kind {
        ITEM("items"), ENTITY("entities"), EFFECT("effects"), FLUID("fluids");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    public final Kind kind;
    public final Identifier id;
    public final ItemStack stack;
    public final EntityType<?> entityType;
    public final MobEffect effect;
    public final Fluid fluid;
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
    /** 流体：同流体的桶物品，取不到时为空栈。 */
    public ItemStack bucket = ItemStack.EMPTY;
    /** 流体：静止贴图，取不到时为 null。 */
    public Identifier stillTexture;
    /** 流体：着色（ARGB），-1 表示未知。 */
    public int fluidColor = -1;
    /** 流体：实际使用的图标来源（cube / texture / bucket / 空）。 */
    public String fluidIconSource = "";
    /** 流体：流动贴图（立方体侧面），取不到时为 null。 */
    public Identifier flowingTexture;
    /** 流体：旧的平面图标（静止贴图/桶），保留给调用方。 */
    public final Map<Integer, BufferedImage> stillIcons = new LinkedHashMap<>();
    /** 流体：平面图标的来源（texture / bucket / 空）。 */
    public String fluidStillIconSource = "";

    private Target(Kind kind, Identifier id, ItemStack stack, EntityType<?> entityType, MobEffect effect,
                   Fluid fluid) {
        this.kind = kind;
        this.id = id;
        this.stack = stack;
        this.entityType = entityType;
        this.effect = effect;
        this.fluid = fluid;
    }

    public static Target item(Identifier id, ItemStack stack) {
        return new Target(Kind.ITEM, id, stack, null, null, null);
    }

    public static Target entity(Identifier id, EntityType<?> type) {
        return new Target(Kind.ENTITY, id, null, type, null, null);
    }

    public static Target effect(Identifier id, MobEffect effect) {
        return new Target(Kind.EFFECT, id, null, null, effect, null);
    }

    public static Target fluid(Identifier id, Fluid fluid) {
        return new Target(Kind.FLUID, id, null, null, null, fluid);
    }

    public String path() {
        return id.getPath();
    }

    public String namespace() {
        return id.getNamespace();
    }
}
