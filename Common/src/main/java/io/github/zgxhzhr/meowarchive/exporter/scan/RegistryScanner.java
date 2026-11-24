package io.github.zgxhzhr.meowarchive.exporter.scan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class RegistryScanner {
    private static final Gson GSON = new Gson();
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
                if (t.nameZh.isBlank() && t.nameEn.isBlank()) {
                    // 技术性实体（中英文名都缺失），不参与导出
                    continue;
                }
                t.entityCategory = type.getCategory().name();
                t.width = type.getDimensions().width;
                t.height = type.getDimensions().height;
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
                    ExporterLog.LOG.debug("实体属性读取失败 {}: {}", id, e.toString());
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

    private static String resolve(Map<String, String> language, String key) {
        if (language == null) {
            return "";
        }
        String value = language.get(key);
        if (value == null || value.equals(key)) {
            String alternate = language.get(key + ".name");
            if (alternate != null && !alternate.equals(key + ".name")) {
                return alternate;
            }
            return "";
        }
        return value;
    }

    /**
     * 直接从资源包读取各命名空间的 lang 文件合并成表。
     */
    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        pair.zh = loadLanguage("zh_cn");
        pair.en = loadLanguage("en_us");
        return pair;
    }

    private static Map<String, String> loadLanguage(String code) {
        Map<String, String> out = new HashMap<>();
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            var files = rm.listResources("lang", loc -> loc.getPath().equals("lang/" + code + ".json"));
            for (var entry : files.entrySet()) {
                try (var in = entry.getValue().open()) {
                    JsonObject json = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    if (json == null) {
                        continue;
                    }
                    for (var field : json.entrySet()) {
                        if (field.getValue().isJsonPrimitive()) {
                            out.put(field.getKey(), field.getValue().getAsString());
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个语言文件损坏不影响其它命名空间
                }
            }
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表加载失败 {}: {}", code, e.toString());
        }
        return out;
    }

    private static String creativeTab(Item item, Minecraft mc) {
        if (creativeTabs == null) {
            creativeTabs = new HashMap<>();
            // 原版只在打开创造模式物品栏时才构建标签页内容，自动导出前先手动构建一次
            try {
                if (mc.level != null && mc.player != null) {
                    CreativeModeTabs.tryRebuildTabContents(
                            mc.level.enabledFeatures(),
                            mc.player.canUseGameMasterBlocks());
                }
            } catch (Throwable e) {
                ExporterLog.LOG.warn("构建创造模式标签页失败: {}", e.toString());
            }
            for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
                // 搜索页包含所有物品，会把真实分类覆盖掉
                if (tab == null || tab.getType() == CreativeModeTab.Type.SEARCH) {
                    continue;
                }
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
                } catch (Throwable e) {
                    ExporterLog.LOG.warn("读取标签页 {} 失败: {}", name, e.toString());
                }
            }
        }
        return creativeTabs.getOrDefault(item, "未知");
    }

    private static final class LanguagePair {
        Map<String, String> zh;
        Map<String, String> en;
    }
}
