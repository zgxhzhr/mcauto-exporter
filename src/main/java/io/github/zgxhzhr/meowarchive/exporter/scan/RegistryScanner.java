package io.github.zgxhzhr.meowarchive.exporter.scan;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.MeowArchive;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

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

public final class RegistryScanner {
    private static Map<Item, String> creativeTabs;
    private static Map<Item, List<ItemStack>> creativeVariants;
    private static Map<Item, String> oreDict;
    /** 创造栏名的解析依赖扫描时构建的语言表，避免依赖客户端 I18n（部分环境读不到模组语言）。 */
    private static LanguagePair scanLang;

    private RegistryScanner() {
    }

    public static List<Target> scan(ExportRequest request) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        LanguagePair lang = loadLanguages();
        scanLang = lang;
        List<Target> out = new ArrayList<>();
        if (request.kinds.contains(Target.Kind.ITEM)) {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = item.getRegistryName();
                if (item == Items.AIR || id == null || !request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                // 1.12 的元数据子类型（羊毛颜色等）按创造栏逐条展开，与 IRR 的粒度一致
                for (ItemStack stack : variants(item)) {
                    Target t = Target.item(id, stack);
                    fillNames(t, lang, item.getUnlocalizedName());
                    t.creativeTab = creativeTab(item);
                    t.tags = oreDictNames(item);
                    out.add(t);
                }
            }
        }
        if (request.kinds.contains(Target.Kind.EFFECT)) {
            for (Potion effect : ForgeRegistries.POTIONS) {
                ResourceLocation id = effect.getRegistryName();
                if (id == null || !request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                Target t = Target.effect(id, effect);
                fillNames(t, lang, effect.getName());
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
            for (EntityEntry entry : ForgeRegistries.ENTITIES) {
                ResourceLocation id = entry.getRegistryName();
                if (id == null || !request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                Target t = Target.entity(id, entry.getEntityClass());
                Entity entity = null;
                try {
                    entity = entry.newInstance(mc.world);
                } catch (Throwable e) {
                    ExporterLog.LOG.debug("实体创建失败 {}: {}", id, e.toString());
                }
                if (entity != null) {
                    String key = entityNameKey(entity);
                    if (key != null) {
                        fillNames(t, lang, key);
                    }
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

    /**
     * 1.12.2 的实体名走 EntityList 的字符串映射，语言键是 entity.&lt;映射名&gt;.name。
     */
    private static String entityNameKey(Entity entity) {
        try {
            String name = EntityList.getEntityString(entity);
            if (name != null && !name.isEmpty()) {
                return "entity." + name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void fillNames(Target t, LanguagePair lang, String key) {
        t.nameZh = resolve(lang.zh, key);
        t.nameEn = resolve(lang.en, key);
    }

    private static String resolve(Map<String, String> language, String key) {
        if (language == null || key == null) {
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

    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        pair.zh = loadLanguage("zh_cn");
        pair.en = loadLanguage("en_us");
        return pair;
    }

    /**
     * 1.12.2 的语言文件是 .lang。先按资源域从资源管理器读，再从模组源文件直读一遍：
     * 某些情况下资源管理器会把自己的资源域判为不存在（模型/贴图正常，但 lang 读不到），
     * 直读模组 jar 可以绕开这个差异，且对目录形式的开发环境同样有效。
     * 不依赖 I18n，因为客户端当前只有一种语言。
     */
    private static Map<String, String> loadLanguage(String code) {
        Map<String, String> out = new HashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        IResourceManager rm = mc.getResourceManager();
        for (String domain : rm.getResourceDomains()) {
            try {
                IResource resource = rm.getResource(new ResourceLocation(domain, "lang/" + code + ".lang"));
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
        for (net.minecraftforge.fml.common.ModContainer container
                : net.minecraftforge.fml.common.Loader.instance().getIndexedModList().values()) {
            try {
                readModLanguage(container.getSource(), code, out);
            } catch (Throwable e) {
                ExporterLog.LOG.debug("读取模组语言失败 {}: {}", container.getModId(), e.toString());
            }
        }
        ExporterLog.LOG.info("语言 {}：{} 条", code, out.size());
        return out;
    }

    private static void readModLanguage(java.io.File source, String code, Map<String, String> out)
            throws Exception {
        if (source == null || !source.exists()) {
            return;
        }
        String suffix = "/lang/" + code + ".lang";
        if (source.isDirectory()) {
            java.io.File assets = new java.io.File(source, "assets");
            java.io.File[] domains = assets.listFiles();
            if (domains == null) {
                return;
            }
            for (java.io.File domain : domains) {
                java.io.File lang = new java.io.File(domain, "lang/" + code + ".lang");
                if (lang.isFile()) {
                    try (InputStream in = new java.io.FileInputStream(lang)) {
                        readLanguage(in, out);
                    }
                }
            }
            return;
        }
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("assets/") && name.endsWith(suffix)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        readLanguage(in, out);
                    }
                }
            }
        } finally {
            zip.close();
        }
    }

    private static void readLanguage(InputStream in, Map<String, String> out) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        }
    }

    private static String creativeTab(Item item) {
        ensureCreativeCache();
        String tab = creativeTabs.get(item);
        return tab == null ? "未知" : tab;
    }

    /** 该物品在创造栏里出现的全部元数据变体；不在创造栏中的物品退回默认栈。 */
    private static List<ItemStack> variants(Item item) {
        ensureCreativeCache();
        List<ItemStack> variants = creativeVariants.get(item);
        if (variants == null || variants.isEmpty()) {
            return Collections.singletonList(new ItemStack(item));
        }
        List<ItemStack> copy = new ArrayList<>(variants);
        copy.sort(Comparator.comparingInt(ItemStack::getMetadata));
        return copy;
    }

    private static void ensureCreativeCache() {
        if (creativeTabs != null) {
            return;
        }
        creativeTabs = new HashMap<>();
        creativeVariants = new HashMap<>();
        for (CreativeTabs tab : CreativeTabs.CREATIVE_TAB_ARRAY) {
            // 搜索页包含所有物品，会把真实分类覆盖掉；hotbar 页在客户端直接抛异常
            if (tab == null || tab == CreativeTabs.SEARCH || tab == CreativeTabs.HOTBAR) {
                continue;
            }
            String name;
            try {
                // 1.12.2 的 getTabLabel() 只有 "modid.xxx"，真正的翻译键是 itemGroup.<label>
                String key = "itemGroup." + tab.getTabLabel();
                name = scanLang == null ? "" : resolve(scanLang.zh, key);
                if (name.isEmpty()) {
                    name = I18n.translateToLocal(key);
                }
                if (name.isEmpty()) {
                    name = "未知";
                }
            } catch (Throwable e) {
                name = "未知";
            }
            NonNullList<ItemStack> items = NonNullList.create();
            try {
                tab.displayAllRelevantItems(items);
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
                    if (existing.getMetadata() == stack.getMetadata()) {
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

    /** 1.12.2 用矿物词典代替标签，把条目所属的全部矿物词典名拼成列表。 */
    private static String oreDictNames(Item item) {
        if (oreDict == null) {
            oreDict = new HashMap<>();
            try {
                for (String name : OreDictionary.getOreNames()) {
                    if (name == null) {
                        continue;
                    }
                    for (ItemStack stack : OreDictionary.getOres(name, false)) {
                        if (stack == null || stack.isEmpty()) {
                            continue;
                        }
                        String existing = oreDict.get(stack.getItem());
                        if (existing == null) {
                            oreDict.put(stack.getItem(), name);
                        } else if (!existing.contains(name)) {
                            oreDict.put(stack.getItem(), existing + "," + name);
                        }
                    }
                }
            } catch (Throwable e) {
                ExporterLog.LOG.warn("读取矿物词典失败: {}", e.toString());
            }
        }
        String names = oreDict.get(item);
        if (names == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        String[] parts = names.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i]);
        }
        return sb.append(']').toString();
    }

    private static final class LanguagePair {
        Map<String, String> zh;
        Map<String, String> en;
    }
}
