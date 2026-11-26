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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class RegistryScanner {
    private static final Gson GSON = new Gson();
    private static final String[] LANGUAGE_CLASS_NAMES = {
            "net.minecraft.locale.Language",          // 官方映射
            "net.minecraft.util.text.LanguageMap"     // 1.15.2 MCP
    };

    private static boolean languageApiChecked;
    private static Method languageForceData;
    private static Method languageGetInstance;
    private static Field languageStorage;
    private static Map<Item, String> creativeTabs;

    private RegistryScanner() {
    }

    public static List<Target> scan(ExportRequest request) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        LanguagePair lang = loadLanguages();
        List<Target> out = new ArrayList<>();
        List<NameJob> nameJobs = new ArrayList<>();
        if (request.kinds.contains(Target.Kind.ITEM)) {
            for (Item item : Registry.ITEM) {
                ResourceLocation id = Registry.ITEM.getKey(item);
                if (item == Items.AIR || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                nameJobs.add(new NameJob(t, item.getDescriptionId(), stack::getHoverName));
                t.creativeTab = creativeTab(item, mc, lang);
                t.tags = itemTags(item);
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
                nameJobs.add(new NameJob(t, effect.getDescriptionId(), effect::getDisplayName));
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
                nameJobs.add(new NameJob(t, type.getDescriptionId(), type::getDescription));
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
        fillNames(nameJobs, lang);
        // 技术性实体（中英文名都缺失）不参与导出，需等名字解析完再剔除
        out.removeIf(t -> t.kind == Target.Kind.ENTITY
                && t.nameZh.trim().isEmpty() && t.nameEn.trim().isEmpty());
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

    /**
     * 用物品/实体的显示名（Component）取值，而不是直接查语言表。
     *
     * <p>模组常用带参数的翻译（格雷科技的材料物品就是 {@code %s 桶} 这类），
     * 直接读语言表只能拿到带 {@code %s} 的模板；Component 会带上参数一起解析。
     * 两种语言靠反射替换语言单例的翻译表实现，取完立即归还。
     */
    private static void fillNames(List<NameJob> jobs, LanguagePair lang) {
        if (jobs.isEmpty()) {
            return;
        }
        Map<String, String> backup = snapshotLanguage();
        if (backup == null) {
            // 拿不到原语言表就不敢替换：直接用当前语言下的显示名兜底，避免导出模板
            for (NameJob job : jobs) {
                String value = clean(rawDisplayName(job));
                job.target.nameZh = value;
                job.target.nameEn = value;
            }
            return;
        }
        try {
            if (lang.zh != null && !lang.zh.isEmpty()) {
                applyLanguage(lang.zh);
                for (NameJob job : jobs) {
                    job.target.nameZh = resolveName(job, lang.zh);
                }
            }
            if (lang.en != null && !lang.en.isEmpty()) {
                applyLanguage(lang.en);
                for (NameJob job : jobs) {
                    job.target.nameEn = resolveName(job, lang.en);
                }
            }
        } finally {
            applyLanguage(backup);
        }
    }

    private static String resolveName(NameJob job, Map<String, String> language) {
        String value = clean(rawDisplayName(job));
        if (unresolved(value, job.key) || hasPlaceholder(value)) {
            return resolve(language, job.key);
        }
        return value;
    }

    private static String rawDisplayName(NameJob job) {
        try {
            Component name = job.displayName.get();
            return name == null ? "" : name.getString();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 官方映射与 1.15.2 MCP 的语言单例类名不同（Language / LanguageMap），
     * 编译期不能直接引用，运行时按序探测；成员名两边一致。
     */
    private static synchronized boolean checkLanguageApi() {
        if (languageApiChecked) {
            return languageForceData != null;
        }
        languageApiChecked = true;
        for (String name : LANGUAGE_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(name);
                Method forceData = clazz.getMethod("forceData", Map.class);
                Method getInstance = clazz.getMethod("getInstance");
                Field storage = clazz.getDeclaredField("storage");
                storage.setAccessible(true);
                languageForceData = forceData;
                languageGetInstance = getInstance;
                languageStorage = storage;
                return true;
            } catch (Throwable ignored) {
                // 该映射名不适用，继续试下一个
            }
        }
        ExporterLog.LOG.warn("找不到可用的语言单例，名称将退回语言表直查");
        return false;
    }

    private static void applyLanguage(Map<String, String> language) {
        if (language == null || !checkLanguageApi()) {
            return;
        }
        try {
            languageForceData.invoke(null, language);
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表切换失败: {}", e.toString());
        }
    }

    /** 反射读出当前语言表的副本，替换前后用它还原。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> snapshotLanguage() {
        if (!checkLanguageApi()) {
            return null;
        }
        try {
            Object instance = languageGetInstance.invoke(null);
            Object value = languageStorage.get(instance);
            if (value instanceof Map) {
                return new HashMap<>((Map<String, String>) value);
            }
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表快照失败: {}", e.toString());
        }
        return null;
    }

    private static String resolve(Map<String, String> language, String key) {
        if (language == null) {
            return "";
        }
        String value = language.get(key);
        if (value == null || value.equals(key)) {
            String alternate = language.get(key + ".name");
            if (alternate != null && !alternate.equals(key + ".name")) {
                alternate = clean(alternate);
                return hasPlaceholder(alternate) ? "" : alternate;
            }
            return "";
        }
        value = clean(value);
        return hasPlaceholder(value) ? "" : value;
    }

    /** 模组名里常带 {@code §r} 这类格式码，写进 JSON 会变成乱码字符。 */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("§.", "").trim();
    }

    /** 翻译缺失时 Component 会原样返回键名，这类值视为无效。 */
    private static boolean unresolved(String value, String key) {
        return value == null || value.trim().isEmpty() || value.equals(key)
                || value.startsWith("item.") || value.startsWith("block.")
                || value.startsWith("entity.") || value.startsWith("effect.");
    }

    /** 原始翻译里可能残留 {@code %s} 这类占位符，导出后会露出模板，视为无效。 */
    private static boolean hasPlaceholder(String value) {
        return value != null && value.matches("(?s).*%\\d*\\$?[sSdDfFxX].*");
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

    /** 待解析名字的条目：注册名的翻译键 + 该条目的显示名组件。 */
    private static final class NameJob {
        final Target target;
        final String key;
        final Supplier<Component> displayName;

        NameJob(Target target, String key, Supplier<Component> displayName) {
            this.target = target;
            this.key = key;
            this.displayName = displayName;
        }
    }
}
