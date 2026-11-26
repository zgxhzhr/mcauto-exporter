package io.github.zgxhzhr.meowarchive.exporter.scan;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
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
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
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
            for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
                ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
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
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (!request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.entity(id, type);
                fillNames(t, lang, type.getDescriptionId(), type::getDescription);
                if (t.nameZh.isBlank() && t.nameEn.isBlank()) {
                    // 技术性实体（无中文也无英文名），不参与导出
                    continue;
                }
                t.entityCategory = type.getCategory().name();
                t.width = type.getDimensions().width();
                t.height = type.getDimensions().height();
                try {
                    Entity entity = type.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
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

    private static String resolve(ClientLanguage language, String key) {
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

    /** 翻译缺失时 Component 会原样返回键名，这类值视为无效。 */
    private static boolean unresolved(String value, String key) {
        return value == null || value.isBlank() || value.equals(key)
                || value.startsWith("item.") || value.startsWith("block.")
                || value.startsWith("entity.") || value.startsWith("effect.");
    }

    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            pair.zh = ClientLanguage.loadFrom(rm, List.of("zh_cn"), false);
            pair.en = ClientLanguage.loadFrom(rm, List.of("en_us"), false);
        } catch (Throwable e) {
            ExporterLog.LOG.warn("语言表加载失败: {}", e.toString());
        }
        return pair;
    }

    private static String creativeTab(Item item, Minecraft mc) {
        if (creativeTabs == null) {
            creativeTabs = new HashMap<>();
            // 原版只在打开创造模式物品栏时才构建标签页内容，自动导出前先手动构建一次
            try {
                if (mc.level != null && mc.player != null) {
                    CreativeModeTabs.tryRebuildTabContents(
                            mc.level.enabledFeatures(),
                            mc.player.canUseGameMasterBlocks(),
                            mc.level.registryAccess());
                }
            } catch (Throwable e) {
                ExporterLog.LOG.warn("构建创造模式标签页失败: {}", e.toString());
            }
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                // 搜索页包含所有物品，会把真实分类覆盖掉
                if (tab.getType() == CreativeModeTab.Type.SEARCH) {
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
        ClientLanguage zh;
        ClientLanguage en;
    }
}
