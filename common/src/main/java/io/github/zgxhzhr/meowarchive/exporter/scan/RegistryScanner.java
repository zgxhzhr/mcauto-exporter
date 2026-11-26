package io.github.zgxhzhr.meowarchive.exporter.scan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
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
import java.util.function.Supplier;
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
            // 1.14.4 的 Registry 不实现 Iterable，统一按 keySet 遍历，跨 loader 也可用。
            for (ResourceLocation id : Registry.ITEM.keySet()) {
                Item item = Registry.ITEM.get(id);
                if (item == Items.AIR || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                fillNames(t, lang, item.getDescriptionId(), stack::getHoverName);
                t.creativeTab = creativeTab(item, mc, lang);
                t.tags = itemTags(item);
                out.add(t);
            }
        }
        if (request.kinds.contains(Target.Kind.EFFECT)) {
            for (ResourceLocation id : Registry.MOB_EFFECT.keySet()) {
                MobEffect effect = Registry.MOB_EFFECT.get(id);
                if (!request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.effect(id, effect);
                fillNames(t, lang, effect.getDescriptionId(), effect::getDisplayName);
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
            for (ResourceLocation id : Registry.ENTITY_TYPE.keySet()) {
                EntityType<?> type = Registry.ENTITY_TYPE.get(id);
                if (!request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.entity(id, type);
                fillNames(t, lang, type.getDescriptionId(), type::getDescription);
                if (t.nameZh.trim().isEmpty() && t.nameEn.trim().isEmpty()) {
                    // 技术性实体（中英文名都缺失），不参与导出
                    continue;
                }
                t.entityCategory = type.getCategory().name();
                t.width = type.getDimensions().width;
                t.height = type.getDimensions().height;
                try {
                    Entity entity = type.create(mc.level);
                    if (entity != null) {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity) {
                            t.maxHealth = ((net.minecraft.world.entity.LivingEntity) entity).getMaxHealth();
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

    /** 1.16.5 没有 ItemStack#getTags，从标签表反查该物品所属标签。 */
    private static String itemTags(Item item) {
        try {
            java.util.Collection<?> tags = ItemTags.getAllTags().getMatchingTags(item);
            List<String> ids = new ArrayList<>();
            for (Object tag : tags) {
                ids.add(String.valueOf(tag));
            }
            java.util.Collections.sort(ids);
            return "[" + String.join(",", ids) + "]";
        } catch (Throwable e) {
            return "[]";
        }
    }

    private static void fillNames(Target t, LanguagePair lang, String descId,
                                  Supplier<Component> displayName) {
        t.nameZh = resolveName(lang.zh, descId, displayName);
        t.nameEn = resolveName(lang.en, descId, displayName);
    }

    /**
     * 语言表命中且不是带参数的模板时直接使用。模组常用 "%s 锭" 这类翻译，
     * 直接读语言表只能拿到带 %s / %1$s 的模板，此时回退到显示名
     * （当前语言，参数已由 ITextComponent 代入），保证导出的不是模板。
     */
    private static String resolveName(Map<String, String> language, String key,
                                      Supplier<Component> displayName) {
        String value = resolve(language, key);
        if (usable(value)) {
            return value;
        }
        try {
            String shown = clean(displayName.get().getString());
            return usable(shown) ? shown : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean usable(String value) {
        return value != null && !value.isEmpty() && !hasFormat(value);
    }

    /** %s / %1$s / %d 这类翻译模板不能直接写进 JSON。 */
    private static boolean hasFormat(String value) {
        return value.matches(".*%\\d*\\$?[sdf].*");
    }

    /** 模组名里常带 §r 这类格式码，写进 JSON 会变成乱码字符。 */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("§.", "").trim();
    }

    private static String resolve(Map<String, String> language, String key) {
        if (language == null) {
            return "";
        }
        String value = clean(language.get(key));
        if (value.isEmpty() || value.equals(key)) {
            String alternate = clean(language.get(key + ".name"));
            if (!alternate.isEmpty() && !alternate.equals(key + ".name")) {
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
            net.minecraft.server.packs.resources.ResourceManager rm = Minecraft.getInstance().getResourceManager();
            java.util.Collection<ResourceLocation> files = rm.listResources("lang",
                    path -> path.endsWith("/" + code + ".json") || path.equals(code + ".json"));
            for (ResourceLocation file : files) {
                fromPack = true;
                try (java.io.InputStream in = rm.getResource(file).getInputStream()) {
                    mergeLanguage(out, in);
                } catch (Throwable ignored) {
                    // 单个语言文件损坏不影响其它命名空间
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
                try (java.io.InputStream in = cl.getResourceAsStream("assets/" + ns + "/lang/" + code + ".json")) {
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
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> field : json.entrySet()) {
            if (field.getValue().isJsonPrimitive()) {
                out.put(field.getKey(), field.getValue().getAsString());
            }
        }
    }

    private static String creativeTab(Item item, Minecraft mc, LanguagePair lang) {
        if (creativeTabs == null) {
            creativeTabs = new HashMap<>();
            for (CreativeModeTab tab : CreativeModeTab.TABS) {
                // 搜索页包含所有物品，会把真实分类覆盖掉；快捷栏页在客户端填充时直接抛异常
                if (tab == null || tab == CreativeModeTab.TAB_SEARCH || tab == CreativeModeTab.TAB_HOTBAR) {
                    continue;
                }
                String name;
                try {
                    // 1.15.2 的 getName() 直接返回翻译键（如 itemGroup.combat），需要自行查语言表
                    name = tabName(tab, lang);
                } catch (Throwable e) {
                    name = "未知";
                }
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

    /** 把创造栏的翻译键（itemGroup.combat）翻成可读名。 */
    private static String tabName(CreativeModeTab tab, LanguagePair lang) {
        String raw = tab.getName();
        if (raw == null || raw.isEmpty()) {
            return "未知";
        }
        String zh = resolve(lang.zh, raw);
        if (!zh.isEmpty()) {
            return zh;
        }
        String en = resolve(lang.en, raw);
        if (!en.isEmpty()) {
            return en;
        }
        return raw;
    }

    private static final class LanguagePair {
        Map<String, String> zh;
        Map<String, String> en;
    }
}
