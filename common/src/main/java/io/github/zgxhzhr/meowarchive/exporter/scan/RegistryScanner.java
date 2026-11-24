package io.github.zgxhzhr.meowarchive.exporter.scan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
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
            for (Item item : Registry.ITEM) {
                ResourceLocation id = Registry.ITEM.getKey(item);
                if (item == Items.AIR || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                fillNames(t, lang, item.getDescriptionId());
                t.creativeTab = creativeTab(item, lang);
                t.tags = net.minecraft.tags.ItemTags.getAllTags()
                        .getMatchingTags(item)
                        .stream()
                        .map(ResourceLocation::toString)
                        .collect(Collectors.joining(",", "[", "]"));
                out.add(t);
            }
        }
        if (request.kinds.contains(Target.Kind.EFFECT)) {
            for (MobEffect effect : Registry.MOB_EFFECT) {
                ResourceLocation id = Registry.MOB_EFFECT.getKey(effect);
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
            for (EntityType<?> type : Registry.ENTITY_TYPE) {
                ResourceLocation id = Registry.ENTITY_TYPE.getKey(type);
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
     * 1.19.2 没有按语言代码加载整张语言表的公开接口，
     * 这里直接从资源包读取各命名空间的 lang 文件合并成表。
     */
    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        pair.zh = loadLanguage("zh_cn");
        pair.en = loadLanguage("en_us");
        return pair;
    }

    private static Map<String, String> loadLanguage(String code) {
        Map<String, String> out = new HashMap<>();
        boolean fromPack = false;
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            // 1.17.1 的 listResources 取不到 assets 目录里的原版语言文件（只从 jar 里列），
            // 这里按命名空间直接取具体文件，原版 zh_cn 在 assets 资源包里也能读到。
            java.util.LinkedHashSet<String> packNamespaces =
                    new java.util.LinkedHashSet<>(
                            io.github.zgxhzhr.meowarchive.exporter.platform.Services.PLATFORM.loadedModIds());
            packNamespaces.add("minecraft");
            for (String ns : packNamespaces) {
                try (var in = rm.getResource(new ResourceLocation(ns, "lang/" + code + ".json"))
                        .getInputStream()) {
                    fromPack = true;
                    mergeLanguage(out, in);
                } catch (Throwable ignored) {
                    // 该命名空间没有这个语言文件
                }
            }
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表（资源包）加载失败 {}: {}", code, e.toString());
        }
        // 资源管理器在某些 loader/资源包组合下拿不到模组内置语言，再从 classpath 直接读一遍兜底。
        int direct = 0;
        try {
            java.util.LinkedHashSet<String> namespaces =
                    new java.util.LinkedHashSet<>(io.github.zgxhzhr.meowarchive.exporter.platform.Services.PLATFORM.loadedModIds());
            namespaces.add("minecraft");
            ClassLoader cl = RegistryScanner.class.getClassLoader();
            for (String ns : namespaces) {
                try (var in = cl.getResourceAsStream("assets/" + ns + "/lang/" + code + ".json")) {
                    if (in == null) {
                        continue;
                    }
                    mergeLanguage(out, in);
                    direct++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表（classpath）加载失败 {}: {}", code, e.toString());
        }
        ExporterLog.LOG.info("语言 {} 加载完成：资源包={} classpath={} 键={}",
                code, fromPack, direct, out.size());
        return out;
    }

    private static void mergeLanguage(Map<String, String> out, java.io.InputStream in) {
        JsonObject json = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        if (json == null) {
            return;
        }
        for (var field : json.entrySet()) {
            if (field.getValue().isJsonPrimitive()) {
                out.put(field.getKey(), field.getValue().getAsString());
            }
        }
    }

    private static String creativeTab(Item item, LanguagePair lang) {
        if (creativeTabs == null) {
            creativeTabs = new HashMap<>();
            for (CreativeModeTab tab : CreativeModeTab.TABS) {
                // 搜索页包含所有物品，会把真实分类覆盖掉
                if (tab == null || tab == CreativeModeTab.TAB_SEARCH) {
                    continue;
                }
                String name = tabName(tab, lang);
                NonNullList<ItemStack> items = NonNullList.create();
                try {
                    tab.fillItemList(items);
                } catch (Throwable e) {
                    ExporterLog.LOG.warn("读取标签页 {} 失败: {}", name, e.toString());
                    continue;
                }
                for (ItemStack stack : items) {
                    creativeTabs.putIfAbsent(stack.getItem(), name);
                }
            }
        }
        return creativeTabs.getOrDefault(item, "未知");
    }

    /** 创造栏名称：1.17 没有公开的翻译键访问器，从显示名里的 TranslatableComponent 取键，
     *  再用已加载的 zh_cn 表翻译；拿不到时退回当前语言的可读名。 */
    private static String tabName(CreativeModeTab tab, LanguagePair lang) {
        try {
            net.minecraft.network.chat.Component display = tab.getDisplayName();
            if (display instanceof net.minecraft.network.chat.TranslatableComponent) {
                String key = ((net.minecraft.network.chat.TranslatableComponent) display).getKey();
                String zh = lang.zh.get(key);
                if (zh != null && !zh.isEmpty()) {
                    return zh;
                }
            }
            return display.getString();
        } catch (Throwable e) {
            return "未知";
        }
    }

    private static final class LanguagePair {
        Map<String, String> zh;
        Map<String, String> en;
    }
}
