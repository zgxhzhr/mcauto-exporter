package io.github.zgxhzhr.mcauto.exporter.scan;

import io.github.zgxhzhr.mcauto.exporter.McAutoExporter;
import io.github.zgxhzhr.mcauto.exporter.core.ExportRequest;
import io.github.zgxhzhr.mcauto.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class RegistryScanner {
    private static Map<Item, String> creativeTabs;

    private RegistryScanner() {
    }

    public static List<Target> scan(ExportRequest request) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        LanguagePair lang = loadLanguages();
        List<Target> out = new ArrayList<>();
        if (request.kinds.contains(Target.Kind.ITEM)) {
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (item == Items.AIR || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                fillNames(t, lang, item.getDescriptionId());
                t.creativeTab = creativeTab(item, mc);
                t.tags = stack.getTags()
                        .map(TagKey::location)
                        .map(ResourceLocation::toString)
                        .collect(Collectors.joining(",", "[", "]"));
                out.add(t);
            }
        }
        if (request.kinds.contains(Target.Kind.EFFECT)) {
            for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
                ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
                if (!request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.effect(id, effect);
                fillNames(t, lang, effect.getDescriptionId());
                t.effectCategory = effect.getCategory().name();
                t.color = effect.getColor();
                t.instant = effect.isInstantenous();
                out.add(t);
            }
        }
        if (request.kinds.contains(Target.Kind.ENTITY)) {
            if (mc.level == null) {
                throw new IllegalStateException("导出实体需要先进入世界");
            }
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (!request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.entity(id, type);
                fillNames(t, lang, type.getDescriptionId());
                t.entityCategory = type.getCategory().name();
                t.width = type.getDimensions().width();
                t.height = type.getDimensions().height();
                try {
                    Entity entity = type.create(mc.level);
                    if (entity != null) {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                            t.maxHealth = living.getMaxHealth();
                        }
                        t.fireImmune = entity.fireImmune();
                        t.tameable = entity instanceof TamableAnimal;
                        try {
                            t.renderer = mc.getEntityRenderDispatcher().getRenderer(entity).getClass().getName();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable e) {
                    McAutoExporter.LOG.debug("实体属性读取失败 {}: {}", id, e.toString());
                }
                out.add(t);
            }
        }
        return out;
    }

    private static void fillNames(Target t, LanguagePair lang, String descId) {
        t.nameZh = resolve(lang.zh, descId);
        t.nameEn = resolve(lang.en, descId);
    }

    private static String resolve(ClientLanguage language, String key) {
        if (language == null) {
            return "";
        }
        String value = language.getOrDefault(key);
        if (value == null || value.equals(key) || value.startsWith("item.") || value.startsWith("entity.")
                || value.startsWith("effect.")) {
            String alternate = language.getOrDefault(key + ".name");
            if (alternate != null && !alternate.equals(key + ".name") && !alternate.startsWith("item.")
                    && !alternate.startsWith("entity.") && !alternate.startsWith("effect.")) {
                return alternate;
            }
            return "";
        }
        return value;
    }

    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            pair.zh = ClientLanguage.loadFrom(rm, List.of("zh_cn"), false);
            pair.en = ClientLanguage.loadFrom(rm, List.of("en_us"), false);
        } catch (Throwable e) {
            McAutoExporter.LOG.warn("语言表加载失败: {}", e.toString());
        }
        return pair;
    }

    private static String creativeTab(Item item, Minecraft mc) {
        if (creativeTabs == null) {
            creativeTabs = new HashMap<>();
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                String name;
                try {
                    name = tab.getDisplayName().getString();
                } catch (Throwable e) {
                    name = "未知";
                }
                try {
                    for (ItemStack stack : tab.getDisplayItems()) {
                        creativeTabs.putIfAbsent(stack.getItem(), name);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return creativeTabs.getOrDefault(item, "未知");
    }

    private static final class LanguagePair {
        ClientLanguage zh;
        ClientLanguage en;
    }
}
