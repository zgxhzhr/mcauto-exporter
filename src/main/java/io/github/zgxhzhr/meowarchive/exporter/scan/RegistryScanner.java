package io.github.zgxhzhr.meowarchive.exporter.scan;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
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
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
            scanItems(request, lang, out);
        }
        if (request.kinds.contains(Target.Kind.EFFECT)) {
            scanEffects(request, lang, out);
        }
        if (request.kinds.contains(Target.Kind.ENTITY)) {
            if (mc.theWorld == null) {
                throw new IllegalStateException("导出实体需要先进入世界");
            }
            scanEntities(request, lang, mc, out);
        }
        return out;
    }

    /** 界面上可选的命名空间集合：只要物品/效果/实体里出现过就算。 */
    @SuppressWarnings("unchecked")
    public static java.util.Set<String> contentNamespaces() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry<Item> registry =
                    cpw.mods.fml.common.registry.GameData.getItemRegistry();
            for (Object keyObj : registry.getKeys()) {
                out.add(toResourceLocation(String.valueOf(keyObj)).getResourceDomain());
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Potion potion : Potion.potionTypes) {
                if (potion != null) {
                    out.add("minecraft");
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Map<String, Class> mapping = EntityList.stringToClassMapping;
            for (Map.Entry<String, Class> entry : mapping.entrySet()) {
                out.add(entityId(entry.getKey(), entry.getValue()).getResourceDomain());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 1.7.10 的实体注册名是 "XPOrb" 这类驼峰名；1.12+ 的注册名是下划线。
     * 原版实体统一映射到现代注册名，保证跨版本归档时同一实体能对齐。
     */
    private static final Map<String, String> VANILLA_ENTITY_IDS = new HashMap<>();

    static {
        VANILLA_ENTITY_IDS.put("caveSpider", "cave_spider");
        VANILLA_ENTITY_IDS.put("enderDragon", "ender_dragon");
        VANILLA_ENTITY_IDS.put("entityHorse", "horse");
        VANILLA_ENTITY_IDS.put("fallingSand", "falling_block");
        VANILLA_ENTITY_IDS.put("lavaSlime", "magma_cube");
        VANILLA_ENTITY_IDS.put("mushroomCow", "mooshroom");
        VANILLA_ENTITY_IDS.put("ozelot", "ocelot");
        VANILLA_ENTITY_IDS.put("pigZombie", "zombie_pigman");
        VANILLA_ENTITY_IDS.put("primedTnt", "tnt");
        VANILLA_ENTITY_IDS.put("smallFireball", "small_fireball");
        VANILLA_ENTITY_IDS.put("snowMan", "snow_golem");
        VANILLA_ENTITY_IDS.put("villagerGolem", "iron_golem");
        VANILLA_ENTITY_IDS.put("witherBoss", "wither");
        VANILLA_ENTITY_IDS.put("xPOrb", "experience_orb");
        VANILLA_ENTITY_IDS.put("giant", "giant");
    }

    private static void scanItems(ExportRequest request, LanguagePair lang, List<Target> out) {
        try {
            cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry<Item> registry =
                    cpw.mods.fml.common.registry.GameData.getItemRegistry();
            for (Object keyObj : registry.getKeys()) {
                String key = String.valueOf(keyObj);
                Object value = registry.getObject(key);
                if (!(value instanceof Item)) {
                    continue;
                }
                Item item = (Item) value;
                ResourceLocation id = toResourceLocation(key);
                if (!request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                for (ItemStack stack : variants(item)) {
                    if (stack == null || stack.getItem() == null) {
                        continue;
                    }
                    Target t = Target.item(id, stack);
                    fillNames(t, lang, stack.getUnlocalizedName() + ".name");
                    t.creativeTab = creativeTab(item);
                    t.tags = oreDictNames(item);
                    out.add(t);
                }
            }
        } catch (Throwable e) {
            ExporterLog.LOG.warn("扫描物品注册表失败: {}", e.toString());
        }
    }

    /**
     * 1.7.10 没有效果注册名，只有 Potion.getName()（驼峰，如 moveSpeed）。
     * 这里映射到 1.12+ 的官方注册名，保证同一效果在不同版本的 registerName 一致。
     */
    private static final Map<String, String> VANILLA_EFFECT_IDS = new HashMap<>();

    static {
        VANILLA_EFFECT_IDS.put("moveSpeed", "speed");
        VANILLA_EFFECT_IDS.put("moveSlowdown", "slowness");
        VANILLA_EFFECT_IDS.put("digSpeed", "haste");
        VANILLA_EFFECT_IDS.put("digSlowdown", "mining_fatigue");
        VANILLA_EFFECT_IDS.put("damageBoost", "strength");
        VANILLA_EFFECT_IDS.put("heal", "instant_health");
        VANILLA_EFFECT_IDS.put("harm", "instant_damage");
        VANILLA_EFFECT_IDS.put("jump", "jump_boost");
        VANILLA_EFFECT_IDS.put("confusion", "nausea");
        VANILLA_EFFECT_IDS.put("regeneration", "regeneration");
        VANILLA_EFFECT_IDS.put("resistance", "resistance");
        VANILLA_EFFECT_IDS.put("fireResistance", "fire_resistance");
        VANILLA_EFFECT_IDS.put("waterBreathing", "water_breathing");
        VANILLA_EFFECT_IDS.put("invisibility", "invisibility");
        VANILLA_EFFECT_IDS.put("blindness", "blindness");
        VANILLA_EFFECT_IDS.put("nightVision", "night_vision");
        VANILLA_EFFECT_IDS.put("hunger", "hunger");
        VANILLA_EFFECT_IDS.put("weakness", "weakness");
        VANILLA_EFFECT_IDS.put("poison", "poison");
        VANILLA_EFFECT_IDS.put("wither", "wither");
        VANILLA_EFFECT_IDS.put("healthBoost", "health_boost");
        VANILLA_EFFECT_IDS.put("absorption", "absorption");
        VANILLA_EFFECT_IDS.put("saturation", "saturation");
    }

    private static void scanEffects(ExportRequest request, LanguagePair lang, List<Target> out) {
        try {
            for (Potion potion : Potion.potionTypes) {
                if (potion == null) {
                    continue;
                }
                String name = potion.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String path = name.startsWith("potion.") ? name.substring("potion.".length()) : name;
                String mapped = VANILLA_EFFECT_IDS.get(path);
                ResourceLocation id = new ResourceLocation("minecraft", mapped == null ? path : mapped);
                if (!request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                Target t = Target.effect(id, potion);
                fillNames(t, lang, "potion." + path);
                t.effectCategory = potion.isBadEffect() ? "HARMFUL"
                        : "BENEFICIAL";
                t.color = potion.getLiquidColor();
                t.instant = potion.isInstant();
                out.add(t);
            }
        } catch (Throwable e) {
            ExporterLog.LOG.warn("扫描药水效果失败: {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanEntities(ExportRequest request, LanguagePair lang, Minecraft mc, List<Target> out) {
        try {
            Map<String, Class> mapping = EntityList.stringToClassMapping;
            for (Map.Entry<String, Class> entry : mapping.entrySet()) {
                String name = entry.getKey();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                ResourceLocation id = entityId(name, entry.getValue());
                if (!request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                Class<?> raw = entry.getValue();
                if (!Entity.class.isAssignableFrom(raw)) {
                    continue;
                }
                Class<? extends Entity> entityClass = (Class<? extends Entity>) raw;
                Target t = Target.entity(id, entityClass);
                Entity entity = null;
                try {
                    // 直接走标准 World 构造器：EntityList.createEntityByName 遇到没有该构造器的
                    // 技术性实体时会向控制台打印 InstantiationException，这里提前判掉。
                    entity = entityClass.getConstructor(net.minecraft.world.World.class)
                            .newInstance(mc.theWorld);
                } catch (NoSuchMethodException e) {
                    ExporterLog.LOG.debug("实体没有 World 构造器，跳过实例化: {}", id);
                } catch (Throwable e) {
                    ExporterLog.LOG.debug("实体创建失败 {}: {}", id, e.toString());
                }
                if (entity != null) {
                    fillNames(t, lang, "entity." + name + ".name");
                    try {
                        if (entity instanceof EntityLivingBase) {
                            t.maxHealth = ((EntityLivingBase) entity).getMaxHealth();
                        }
                        t.fireImmune = entity.isImmuneToFire();
                        t.tameable = entity instanceof EntityTameable;
                        try {
                            Object renderer = net.minecraft.client.renderer.entity.RenderManager.instance
                                    .getEntityRenderObject(entity);
                            if (renderer != null) {
                                t.renderer = renderer.getClass().getName();
                            }
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
        } catch (Throwable e) {
            ExporterLog.LOG.warn("扫描实体注册表失败: {}", e.toString());
        }
    }

    /**
     * 1.7.10 的实体注册名没有命名空间：原版是 "Zombie" 这类大写简单名，
     * 模组常用 "modid.EntityName"。原版实体归入 minecraft，其余尽力还原模组 id。
     */
    private static ResourceLocation entityId(String name, Class<?> entityClass) {
        if (name.indexOf(':') > 0) {
            return new ResourceLocation(name);
        }
        if (entityClass != null && entityClass.getName().startsWith("net.minecraft.")) {
            // EntityList 的键首字母大写（"FallingSand"），映射表用小驼峰，两种都查
            String mapped = VANILLA_ENTITY_IDS.get(name);
            if (mapped == null) {
                mapped = VANILLA_ENTITY_IDS.get(lowerFirst(name));
            }
            return new ResourceLocation("minecraft", mapped == null ? lowerFirst(name) : mapped);
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            return new ResourceLocation(name.substring(0, dot), name.substring(dot + 1));
        }
        return new ResourceLocation("unknown", lowerFirst(name));
    }

    private static String lowerFirst(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static ResourceLocation toResourceLocation(String key) {
        return key.indexOf(':') > 0 ? new ResourceLocation(key)
                : new ResourceLocation("minecraft", key);
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
        pair.zh = loadLanguage("zh_CN");
        pair.en = loadLanguage("en_US");
        return pair;
    }

    /**
     * 1.7.10 的语言文件是 assets/&lt;domain&gt;/lang/&lt;code&gt;.lang（代码大写）。
     * 先按资源域从资源管理器读，再从模组源文件直读一遍：某些情况下资源管理器会把自己的
     * 资源域判为不存在（模型/贴图正常，但 lang 读不到），直读模组 jar 可以绕开这个差异。
     * 不依赖 StatCollector，因为客户端当前只有一种语言。
     */
    private static Map<String, String> loadLanguage(String code) {
        Map<String, String> out = new HashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        try {
            IResourceManager rm = mc.getResourceManager();
            if (rm instanceof IReloadableResourceManager) {
                Set<String> domains = ((IReloadableResourceManager) rm).getResourceDomains();
                for (String domain : domains) {
                    try {
                        IResource resource = rm.getResource(
                                new ResourceLocation(domain, "lang/" + code + ".lang"));
                        if (resource != null) {
                            try (InputStream in = resource.getInputStream()) {
                                readLanguage(in, out);
                            }
                        }
                    } catch (Throwable ignored) {
                        // 该命名空间没有这个语言文件，不影响其它命名空间
                    }
                }
            }
        } catch (Throwable e) {
            ExporterLog.LOG.debug("资源管理器语言读取失败: {}", e.toString());
        }
        try {
            for (ModContainer container : Loader.instance().getModList()) {
                try {
                    readModLanguage(container.getSource(), code, out);
                } catch (Throwable e) {
                    ExporterLog.LOG.debug("读取模组语言失败 {}: {}", container.getModId(), e.toString());
                }
            }
        } catch (Throwable ignored) {
        }
        readVanillaLanguage(code, out);
        ExporterLog.LOG.info("语言 {}：{} 条", code, out.size());
        return out;
    }

    /** 原版语言在客户端 jar 里，Minecraft 类所在的代码源即是该 jar。 */
    private static void readVanillaLanguage(String code, Map<String, String> out) {
        try {
            File source = new File(Minecraft.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            scanSource(source, code, out);
        } catch (Throwable e) {
            ExporterLog.LOG.debug("读取原版语言失败: {}", e.toString());
        }
    }

    private static void readModLanguage(File source, String code, Map<String, String> out)
            throws Exception {
        scanSource(source, code, out);
    }

    private static void scanSource(File source, String code, Map<String, String> out) throws Exception {
        if (source == null || !source.exists()) {
            return;
        }
        String suffix = "/lang/" + code + ".lang";
        if (source.isDirectory()) {
            File assets = new File(source, "assets");
            File[] domains = assets.listFiles();
            if (domains == null) {
                return;
            }
            for (File domain : domains) {
                File lang = new File(domain, "lang/" + code + ".lang");
                if (lang.isFile()) {
                    try (InputStream in = new FileInputStream(lang)) {
                        readLanguage(in, out);
                    }
                }
            }
            return;
        }
        ZipFile zip = new ZipFile(source);
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
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
        copy.sort(Comparator.comparingInt(ItemStack::getItemDamage));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void ensureCreativeCache() {
        if (creativeTabs != null) {
            return;
        }
        creativeTabs = new HashMap<>();
        creativeVariants = new HashMap<>();
        for (CreativeTabs tab : CreativeTabs.creativeTabArray) {
            if (tab == null) {
                continue;
            }
            // 搜索页包含所有物品，会把真实分类覆盖掉；物品栏页会直接抛异常
            String label = tab.getTabLabel();
            if ("search".equals(label) || "inventory".equals(label)) {
                continue;
            }
            String name;
            try {
                // 1.7.10 的 getTabLabel() 只有 "modid.xxx"，真正的翻译键是 itemGroup.<label>
                String key = "itemGroup." + label;
                name = scanLang == null ? "" : resolve(scanLang.zh, key);
                if (name.isEmpty()) {
                    name = resolve(scanLang.en, key);
                }
                if (name.isEmpty()) {
                    name = "未知";
                }
            } catch (Throwable e) {
                name = "未知";
            }
            List<ItemStack> items = new ArrayList<>();
            try {
                tab.displayAllReleventItems(items);
            } catch (Throwable e) {
                ExporterLog.LOG.warn("读取标签页 {} 失败: {}", name, e.toString());
                continue;
            }
            for (ItemStack stack : items) {
                if (stack == null || stack.getItem() == null) {
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
                    if (existing.getItemDamage() == stack.getItemDamage()) {
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

    /** 1.7.10 用矿物词典代替标签，把条目所属的全部矿物词典名拼成列表。 */
    private static String oreDictNames(Item item) {
        if (oreDict == null) {
            oreDict = new HashMap<>();
            try {
                for (String name : OreDictionary.getOreNames()) {
                    if (name == null) {
                        continue;
                    }
                    for (ItemStack stack : OreDictionary.getOres(name)) {
                        if (stack == null || stack.getItem() == null) {
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
