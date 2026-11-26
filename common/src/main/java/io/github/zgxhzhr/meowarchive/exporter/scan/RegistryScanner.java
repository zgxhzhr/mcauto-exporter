package io.github.zgxhzhr.meowarchive.exporter.scan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.FormattedCharSequence;
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
            for (Item item : Registry.ITEM) {
                ResourceLocation id = Registry.ITEM.getKey(item);
                if (item == Items.AIR || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                fillNames(t, lang, item.getDescriptionId(), stack::getHoverName);
                t.creativeTab = creativeTab(item, mc);
                t.tags = stack.getTags()
                        .map(TagKey::location)
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
            for (EntityType<?> type : Registry.ENTITY_TYPE) {
                ResourceLocation id = Registry.ENTITY_TYPE.getKey(type);
                if (!request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.entity(id, type);
                fillNames(t, lang, type.getDescriptionId(), type::getDescription);
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

    private static void fillNames(Target t, LanguagePair lang, String descId,
                                  Supplier<Component> displayName) {
        t.nameZh = resolveName(lang.zh, descId, displayName);
        t.nameEn = resolveName(lang.en, descId, displayName);
    }

    /**
     * 用物品/实体的显示名（Component）取值，而不是直接查语言表。
     *
     * <p>模组常用带参数的翻译（格雷科技的材料物品就是 {@code %s 桶} 这类），
     * 直接读语言表只能拿到带 {@code %s} 的模板；Component 会带上参数一起解析。
     * 两种语言靠临时注入 {@link Language} 实例实现，取完立即恢复。
     */
    private static String resolveName(Language language, String key, Supplier<Component> name) {
        if (language == null) {
            return "";
        }
        Language original = Language.getInstance();
        try {
            Language.inject(language);
            String value = clean(name.get().getString());
            if (unresolved(value, key)) {
                return resolve(language, key);
            }
            return value;
        } catch (Throwable e) {
            return "";
        } finally {
            Language.inject(original);
        }
    }

    private static String resolve(Language language, String key) {
        if (language == null || key == null || key.isBlank()) {
            return "";
        }
        Language original = Language.getInstance();
        try {
            Language.inject(language);
            String value = clean(Component.translatable(key).getString());
            if (unresolved(value, key)) {
                String alternateKey = key + ".name";
                if (language.has(alternateKey)) {
                    String alternate = clean(Component.translatable(alternateKey).getString());
                    if (!unresolved(alternate, alternateKey)) {
                        return alternate;
                    }
                }
                return "";
            }
            return value;
        } catch (Throwable ignored) {
            return "";
        } finally {
            Language.inject(original);
        }
    }

    /** 模组名里常带 {@code §r} 这类格式码，写进 JSON 会变成乱码字符。 */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("§.", "").trim();
    }

    /** 翻译缺失时 Component 会原样返回键名，带 {@code %s} 的残留模板同样视为无效。 */
    private static boolean unresolved(String value, String key) {
        return value == null || value.isBlank() || value.equals(key)
                || value.contains("%s")
                || value.startsWith("item.") || value.startsWith("block.")
                || value.startsWith("entity.") || value.startsWith("effect.");
    }

    /**
     * 1.19.2 没有按语言代码加载整张语言表的公开接口，
     * 这里直接从资源包读取各命名空间的 lang 文件合并成表，再包成 Language 供临时注入。
     */
    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        pair.zh = new MapLanguage(loadLanguage("zh_cn"));
        pair.en = new MapLanguage(loadLanguage("en_us"));
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
            for (CreativeModeTab tab : CreativeModeTab.TABS) {
                // 搜索页包含所有物品，会把真实分类覆盖掉
                if (tab == null || tab == CreativeModeTab.TAB_SEARCH) {
                    continue;
                }
                String name;
                try {
                    name = tab.getDisplayName().getString();
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

    private static final class LanguagePair {
        Language zh;
        Language en;
    }

    /** 用合并后的语言表包一个 Language，便于临时注入解析带参数的翻译。 */
    private static final class MapLanguage extends Language {
        private final Map<String, String> translations;

        MapLanguage(Map<String, String> translations) {
            this.translations = translations;
        }

        @Override
        public String getOrDefault(String key) {
            return translations.getOrDefault(key, key);
        }

        @Override
        public boolean has(String key) {
            return translations.containsKey(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return false;
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return FormattedCharSequence.forward(text.getString(), Style.EMPTY);
        }
    }
}
