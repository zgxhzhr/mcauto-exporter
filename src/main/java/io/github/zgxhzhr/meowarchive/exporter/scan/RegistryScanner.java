package io.github.zgxhzhr.meowarchive.exporter.scan;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RegistryScanner {
    private static Map<Item, String> creativeTabs;
    private static Map<Item, String> oreDict;

    private RegistryScanner() {
    }

    public static List<Target> scan(ExportRequest request) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        LanguagePair lang = loadLanguages();
        List<Target> out = new ArrayList<>();
        if (request.kinds.contains(Target.Kind.ITEM)) {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = item.getRegistryName();
                if (item == Items.AIR || id == null || !request.namespaces.contains(id.getResourceDomain())) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                Target t = Target.item(id, stack);
                fillNames(t, lang, item.getUnlocalizedName());
                t.creativeTab = creativeTab(item);
                t.tags = oreDictNames(item);
                out.add(t);
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

    /**
     * 1.12.2 的语言文件是 .lang，这里直接按命名空间读取 zh_cn / en_us 并合并成表。
     * 只读资源包里的文件，不能依赖 I18n，因为客户端当前只有一种语言。
     */
    private static LanguagePair loadLanguages() {
        LanguagePair pair = new LanguagePair();
        pair.zh = loadLanguage("zh_cn");
        pair.en = loadLanguage("en_us");
        return pair;
    }

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
                try (InputStream in = resource.getInputStream();
                     BufferedReader reader = new BufferedReader(
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
            } catch (Throwable ignored) {
                // 该命名空间没有这个语言文件，或文件损坏，不影响其它命名空间
            }
        }
        return out;
    }

    private static String creativeTab(Item item) {
        if (creativeTabs == null) {
            creativeTabs = new HashMap<>();
            for (CreativeTabs tab : CreativeTabs.CREATIVE_TAB_ARRAY) {
                // 搜索页包含所有物品，会把真实分类覆盖掉；hotbar 页在客户端直接抛异常
                if (tab == null || tab == CreativeTabs.SEARCH || tab == CreativeTabs.HOTBAR) {
                    continue;
                }
                String name;
                try {
                    name = I18n.translateToLocal(tab.getTranslatedTabLabel());
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
                    if (stack != null && !stack.isEmpty() && !creativeTabs.containsKey(stack.getItem())) {
                        creativeTabs.put(stack.getItem(), name);
                    }
                }
            }
        }
        String tab = creativeTabs.get(item);
        return tab == null ? "未知" : tab;
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
