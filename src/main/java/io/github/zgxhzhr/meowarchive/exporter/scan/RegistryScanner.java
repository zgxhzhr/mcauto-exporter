package io.github.zgxhzhr.meowarchive.exporter.scan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class RegistryScanner {
    private static Map<Item, String> creativeTabs;
    private static Map<Item, List<ItemStack>> creativeVariants;
    /** 创造栏名的解析依赖扫描时构建的语言表，避免依赖客户端 I18n（部分环境读不到模组语言）。 */
    private static LanguagePair scanLang;

    private RegistryScanner() {
    }

    public static List<Target> scan(ExportRequest request) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        LanguagePair lang = loadLanguages();
        scanLang = lang;
        List<Target> out = new ArrayList<>();
        if (request.kinds.contains(Target.Kind.ITEM)) {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = item.getRegistryName();
                if (item == Items.AIR || id == null || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                // 1.13 的变体（药水、附魔书等）靠 NBT 区分，按创造栏逐条展开
                for (ItemStack stack : variants(item)) {
                    Target t = Target.item(id, stack);
                    fillNames(t, lang, stack.getTranslationKey(), stack::getDisplayName);
                    t.creativeTab = creativeTab(item);
                    // 1.13 移除了矿物词典，改用标签体系；这里保持空列表以维持输出结构
                    t.tags = "[]";
                    out.add(t);
                }
            }
        }
        if (request.kinds.contains(Target.Kind.EFFECT)) {
            for (Potion effect : ForgeRegistries.POTIONS) {
                ResourceLocation id = effect.getRegistryName();
                if (id == null || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                Target t = Target.effect(id, effect);
                fillNames(t, lang, effect.getName(), effect::getDisplayName);
                t.effectCategory = effect.isBeneficial() ? "BENEFICIAL"
                        : (effect.isBadEffect() ? "HARMFUL" : "NEUTRAL");
                t.color = effect.getLiquidColor();
                t.instant = effect.isInstant();
                out.add(t);
            }
        }
        if (request.kinds.contains(Target.Kind.ENTITY)) {
            if (mc.world == null) {
                throw new IllegalStateException("导出实体需要先进入世界");
            }
            for (EntityType<?> type : ForgeRegistries.ENTITIES) {
                ResourceLocation id = type.getRegistryName();
                if (id == null || !request.namespaces.contains(id.getNamespace())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Class<? extends Entity> entityClass = (Class<? extends Entity>) type.getEntityClass();
                Target t = Target.entity(id, entityClass);
                Entity entity = null;
                try {
                    entity = type.create(mc.world);
                } catch (Throwable e) {
                    ExporterLog.LOG.debug("实体创建失败 {}: {}", id, e.toString());
                }
                if (entity != null) {
                    fillNames(t, lang, type.getTranslationKey(), type::func_212546_e);
                    try {
                        if (entity instanceof EntityLivingBase) {
                            t.maxHealth = ((EntityLivingBase) entity).getMaxHealth();
                        }
                        t.fireImmune = entity.isImmuneToFire();
                        t.tameable = entity instanceof EntityTameable;
                        try {
                            t.renderer = mc.getRenderManager().getEntityRenderObject(entity)
                                    .getClass().getName();
                        } catch (Throwable ignored) {
                        }
                    } catch (Throwable e) {
                        ExporterLog.LOG.debug("实体属性读取失败 {}: {}", id, e.toString());
                    }
                    t.width = entity.width;
                    t.height = entity.height;
                }
                if (t.nameZh.isEmpty() && t.nameEn.isEmpty()) {
                    // 技术性实体（中英文名都缺失），不参与导出
                    continue;
                }
                out.add(t);
            }
        }
        return out;
    }

    private static void fillNames(Target t, LanguagePair lang, String key,
                                  Supplier<ITextComponent> display) {
        t.nameZh = resolveName(lang.zh, lang.en, key, display);
        t.nameEn = resolveName(lang.en, lang.zh, key, display);
    }

    /**
     * 用显示名组件取值，按目标语言重新解析翻译参数。
     *
     * <p>模组常用带参数的翻译（「%s 桶」这类），直接读语言表只能拿到带占位符的模板；
     * 组件里带着实参，可以按各自语言格式化。当前语言仍解析不出来时用另一语言的显示名兜底，
     * 保证不会把可见的 {@code %s} 写进 JSON。
     */
    private static String resolveName(Map<String, String> language, Map<String, String> other,
                                      String key, Supplier<ITextComponent> display) {
        if (language == null) {
            return "";
        }
        String value = renderDisplay(display, language);
        if (!unresolved(value, key)) {
            return value;
        }
        value = resolve(language, key);
        if (!unresolved(value, key)) {
            return value;
        }
        String alternate = renderDisplay(display, other);
        if (unresolved(alternate, key)) {
            alternate = resolve(other, key);
        }
        return unresolved(alternate, key) ? "" : alternate;
    }

    private static String renderDisplay(Supplier<ITextComponent> display, Map<String, String> language) {
        if (display == null || language == null) {
            return "";
        }
        try {
            return clean(renderComponent(display.get(), language));
        } catch (Throwable e) {
            return "";
        }
    }

    /** 递归展开组件：翻译组件按目标语言取模板并用实参格式化，其余组件保持原文。 */
    private static String renderComponent(ITextComponent component, Map<String, String> language) {
        if (component == null) {
            return "";
        }
        String self;
        if (component instanceof TextComponentTranslation) {
            TextComponentTranslation translation = (TextComponentTranslation) component;
            self = format(language, translation.getKey(), translation.getFormatArgs());
        } else {
            self = component.getUnformattedComponentText();
        }
        StringBuilder out = new StringBuilder(self == null ? "" : self);
        for (ITextComponent sibling : component.getSiblings()) {
            out.append(renderComponent(sibling, language));
        }
        return out.toString();
    }

    private static String format(Map<String, String> language, String key, Object[] args) {
        if (language == null || key == null) {
            return "";
        }
        String template = language.get(key);
        if (template == null) {
            template = language.get(key + ".name");
        }
        if (template == null) {
            return "";
        }
        if (args == null) {
            args = new Object[0];
        }
        Object[] resolved = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolved[i] = args[i] instanceof ITextComponent
                    ? renderComponent((ITextComponent) args[i], language) : args[i];
        }
        try {
            return String.format(template, resolved);
        } catch (RuntimeException e) {
            // 模板需要的参数比组件提供的多，交给上层按无效处理
            return "";
        }
    }

    private static String resolve(Map<String, String> language, String key) {
        if (language == null || key == null) {
            return "";
        }
        String value = language.get(key);
        if (value == null || value.equals(key)) {
            String alternate = language.get(key + ".name");
            if (alternate != null && !alternate.equals(key + ".name")) {
                return clean(alternate);
            }
            return "";
        }
        return clean(value);
    }

    /** 模组名里常带 §r 这类格式码，写进 JSON 会变成乱码字符。 */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("§.", "").trim();
    }

    /** 翻译缺失时会原样返回键名；仍带 % 说明参数没解析出来，同样视为无效。 */
    private static boolean unresolved(String value, String key) {
        return value == null || value.isEmpty() || value.equals(key)
                || value.indexOf('%') >= 0
                || value.startsWith("item.") || value.startsWith("block.")
                || value.startsWith("entity.") || value.startsWith("effect.")
                || value.startsWith("Format error");
    }

    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        pair.zh = loadLanguage("zh_cn");
        pair.en = loadLanguage("en_us");
        return pair;
    }

    /**
     * 1.13 的语言文件是 JSON，直接按资源域从资源管理器读取。
     * 不依赖 I18n，因为客户端当前只有一种语言。
     */
    private static Map<String, String> loadLanguage(String code) {
        Map<String, String> out = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        IResourceManager rm = mc.getResourceManager();
        for (String domain : rm.getResourceNamespaces()) {
            try (IResource resource =
                         rm.getResource(new ResourceLocation(domain, "lang/" + code + ".json"))) {
                if (resource == null) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    readLanguage(in, out);
                }
            } catch (Throwable ignored) {
                // 该命名空间没有这个语言文件，或资源管理器读不到，不影响其它命名空间
            }
        }
        ExporterLog.LOG.info("语言 {}：{} 条", code, out.size());
        return out;
    }

    private static void readLanguage(InputStream in, Map<String, String> out) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject json = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    out.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
    }

    private static String creativeTab(Item item) {
        ensureCreativeCache();
        String tab = creativeTabs.get(item);
        return tab == null ? "未知" : tab;
    }

    /** 该物品在创造栏里出现的全部变体；不在创造栏中的物品退回默认栈。 */
    private static List<ItemStack> variants(Item item) {
        ensureCreativeCache();
        List<ItemStack> variants = creativeVariants.get(item);
        if (variants == null || variants.isEmpty()) {
            return Collections.singletonList(new ItemStack(item));
        }
        List<ItemStack> copy = new ArrayList<>(variants);
        copy.sort(Comparator.comparingInt(ItemStack::getDamage)
                .thenComparing(RegistryScanner::variantKey));
        return copy;
    }

    private static void ensureCreativeCache() {
        if (creativeTabs != null) {
            return;
        }
        creativeTabs = new HashMap<>();
        creativeVariants = new HashMap<>();
        for (ItemGroup tab : ItemGroup.GROUPS) {
            // 搜索页包含所有物品，会把真实分类覆盖掉；hotbar 页在客户端直接抛异常
            if (tab == null || tab == ItemGroup.SEARCH || tab == ItemGroup.HOTBAR) {
                continue;
            }
            String key = tab.getTranslationKey();
            String name = scanLang == null ? "" : resolve(scanLang.zh, key);
            if (name == null || name.isEmpty()) {
                try {
                    name = I18n.format(key);
                } catch (Throwable ignored) {
                    name = "";
                }
            }
            if (name == null || name.isEmpty() || name.equals(key)) {
                name = "未知";
            }
            NonNullList<ItemStack> items = NonNullList.create();
            try {
                tab.fill(items);
            } catch (Throwable e) {
                ExporterLog.LOG.warn("读取标签页 {} 失败: {}", name, e.toString());
                continue;
            }
            for (ItemStack stack : items) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                Item item = stack.getItem();
                if (!creativeTabs.containsKey(item)) {
                    creativeTabs.put(item, name);
                }
                List<ItemStack> variants = creativeVariants.get(item);
                if (variants == null) {
                    variants = new ArrayList<>();
                    creativeVariants.put(item, variants);
                }
                boolean seen = false;
                for (ItemStack existing : variants) {
                    if (variantKey(existing).equals(variantKey(stack))) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    variants.add(stack.copy());
                }
            }
        }
    }

    /** 变体区分键：1.13 没有元数据，用损伤值 + NBT 字符串。 */
    private static String variantKey(ItemStack stack) {
        return stack.getDamage() + "|" + (stack.getTag() == null ? "" : stack.getTag().toString());
    }

    private static final class LanguagePair {
        Map<String, String> zh;
        Map<String, String> en;
    }
}
