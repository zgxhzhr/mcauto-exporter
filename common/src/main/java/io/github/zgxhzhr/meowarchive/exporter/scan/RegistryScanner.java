package io.github.zgxhzhr.meowarchive.exporter.scan;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
            for (Item item : Registry.ITEM) {
                ResourceLocation id = Registry.ITEM.getKey(item);
                if (item == Items.AIR || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                fillNames(t, lang, item.getDescriptionId(), stack::getHoverName);
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
                t.entityCategory = type.getCategory().name();
                if (t.nameZh.isBlank() && t.nameEn.isBlank()
                        && type.getCategory() == net.minecraft.world.entity.MobCategory.MISC) {
                    // 只跳过「无名字 + MISC」的技术性实体（投射物、辅助实体）。
                    // 无名字但属于 CREATURE/AMBIENT 等类别的是真生物（模组漏写实体翻译很常见），
                    // 必须照常导出，名字回退为注册名。
                    ExporterLog.LOG.info("跳过技术性实体（无名字 + MISC）：{}", id);
                    continue;
                }
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
    private static String resolveName(ClientLanguage language, String key, Supplier<Component> name) {
        if (language == null) {
            return "";
        }
        Language original = Language.getInstance();
        try {
            Language.inject(language);
            String value = clean(name.get().getString());
            if (unresolved(value, key) || hasPlaceholder(value)) {
                return resolve(language, key);
            }
            return value;
        } catch (Throwable e) {
            return "";
        } finally {
            Language.inject(original);
        }
    }

    private static String resolve(ClientLanguage language, String key) {
        if (language == null || key == null || key.isBlank()) {
            return "";
        }
        Language original = Language.getInstance();
        try {
            Language.inject(language);
            String value = clean(new TranslatableComponent(key).getString());
            if (unresolved(value, key) || hasPlaceholder(value)) {
                String alternateKey = key + ".name";
                if (language.has(alternateKey)) {
                    String alternate = clean(new TranslatableComponent(alternateKey).getString());
                    if (!unresolved(alternate, alternateKey) && !hasPlaceholder(alternate)) {
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

    /** 翻译缺失时 Component 会原样返回键名，这类值视为无效。 */
    private static boolean unresolved(String value, String key) {
        return value == null || value.isBlank() || value.equals(key)
                || value.startsWith("item.") || value.startsWith("block.")
                || value.startsWith("entity.") || value.startsWith("effect.");
    }

    /** 原始翻译里可能残留 {@code %s} 这类占位符，导出后会露出模板，视为无效。 */
    private static boolean hasPlaceholder(String value) {
        return value != null && value.matches("(?s).*%\\d*\\$?[sSdDfFxX].*");
    }

    /**
     * 1.17.1 的 ClientLanguage 与 1.18.2 相同，可按语言代码整表加载：
     * loadFrom 会遍历资源管理器里的命名空间，把原版与模组的 lang 文件合并。
     */
    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            pair.zh = ClientLanguage.loadFrom(rm, List.of(languageInfo("zh_cn")));
            pair.en = ClientLanguage.loadFrom(rm, List.of(languageInfo("en_us")));
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表加载失败: {}", e.toString());
        }
        return pair;
    }

    /** 语言列表里没有该代码时按代码构造，loadFrom 只用到 getCode。 */
    private static LanguageInfo languageInfo(String code) {
        LanguageInfo info = Minecraft.getInstance().getLanguageManager().getLanguage(code);
        return info != null ? info : new LanguageInfo(code, code, code, false);
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
            Component display = tab.getDisplayName();
            if (display instanceof TranslatableComponent) {
                String zh = resolve(lang.zh, ((TranslatableComponent) display).getKey());
                if (!zh.isEmpty()) {
                    return zh;
                }
            }
            return display.getString();
        } catch (Throwable e) {
            return "未知";
        }
    }

    private static final class LanguagePair {
        ClientLanguage zh;
        ClientLanguage en;
    }
}
