package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.item.ItemBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 按命名空间分目录输出 LMS 兼容 JSON：&lt;ns&gt;/&lt;ns&gt;_items.json、&lt;ns&gt;/&lt;ns&gt;_entities.json。
 * 效果为本仓库扩展，写入 &lt;ns&gt;/&lt;ns&gt;_effects.json；另输出合并文件 &lt;ns&gt;/&lt;ns&gt;_all.json，
 * 包含该命名空间下全部条目，每条附加 kind 字段（item / entity / effect）。
 */
public final class LmsWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private LmsWriter() {
    }

    public static void write(Path root, List<Target> targets, boolean includeIcons, String neutralAs)
            throws IOException {
        Map<String, List<Target>> items = new LinkedHashMap<>();
        Map<String, List<Target>> entities = new LinkedHashMap<>();
        Map<String, List<Target>> effects = new LinkedHashMap<>();
        for (Target target : targets) {
            if (target.kind == Target.Kind.ITEM) {
                group(items, target.namespace()).add(target);
            } else if (target.kind == Target.Kind.ENTITY) {
                group(entities, target.namespace()).add(target);
            } else if (target.kind == Target.Kind.EFFECT) {
                group(effects, target.namespace()).add(target);
            }
        }
        for (Map.Entry<String, List<Target>> entry : items.entrySet()) {
            writeJsonl(root.resolve(entry.getKey()).resolve(entry.getKey() + "_items.json"),
                    itemLines(entry.getValue(), includeIcons));
        }
        for (Map.Entry<String, List<Target>> entry : entities.entrySet()) {
            writeJsonl(root.resolve(entry.getKey()).resolve(entry.getKey() + "_entities.json"),
                    entityLines(entry.getValue(), includeIcons));
        }
        for (Map.Entry<String, List<Target>> entry : effects.entrySet()) {
            writeJsonl(root.resolve(entry.getKey()).resolve(entry.getKey() + "_effects.json"),
                    effectLines(entry.getValue(), includeIcons, neutralAs));
        }
        // 合并文件：物品 → 实体 → 效果依次排列，每条补 kind 字段
        TreeSet<String> namespaces = new TreeSet<>();
        namespaces.addAll(items.keySet());
        namespaces.addAll(entities.keySet());
        namespaces.addAll(effects.keySet());
        for (String ns : namespaces) {
            List<JsonObject> lines = new ArrayList<>();
            appendMerged(lines, items.get(ns), includeIcons, neutralAs);
            appendMerged(lines, entities.get(ns), includeIcons, neutralAs);
            appendMerged(lines, effects.get(ns), includeIcons, neutralAs);
            writeJsonl(root.resolve(ns).resolve(ns + "_all.json"), lines);
        }
    }

    private static List<Target> group(Map<String, List<Target>> map, String ns) {
        List<Target> list = map.get(ns);
        if (list == null) {
            list = new ArrayList<>();
            map.put(ns, list);
        }
        return list;
    }

    private static List<JsonObject> itemLines(List<Target> targets, boolean includeIcons) {
        List<JsonObject> lines = new ArrayList<>();
        for (Target t : sorted(targets)) {
            lines.add(itemJson(t, includeIcons));
        }
        return lines;
    }

    private static List<JsonObject> entityLines(List<Target> targets, boolean includeIcons) {
        List<JsonObject> lines = new ArrayList<>();
        for (Target t : sorted(targets)) {
            lines.add(entityJson(t, includeIcons));
        }
        return lines;
    }

    private static List<JsonObject> effectLines(List<Target> targets, boolean includeIcons, String neutralAs) {
        List<JsonObject> lines = new ArrayList<>();
        for (Target t : sorted(targets)) {
            lines.add(effectJson(t, includeIcons, neutralAs));
        }
        return lines;
    }

    private static List<Target> sorted(List<Target> targets) {
        List<Target> copy = new ArrayList<>(targets);
        copy.sort(Comparator.comparing(t -> t.id.toString()));
        return copy;
    }

    private static void appendMerged(List<JsonObject> lines, List<Target> targets,
                                     boolean includeIcons, String neutralAs) {
        if (targets == null) {
            return;
        }
        for (Target t : sorted(targets)) {
            JsonObject o;
            if (t.kind == Target.Kind.ITEM) {
                o = itemJson(t, includeIcons);
            } else if (t.kind == Target.Kind.ENTITY) {
                o = entityJson(t, includeIcons);
            } else {
                o = effectJson(t, includeIcons, neutralAs);
            }
            o.addProperty("kind", t.kind.name().toLowerCase(Locale.ROOT));
            lines.add(o);
        }
    }

    private static JsonObject itemJson(Target t, boolean includeIcons) {
        JsonObject o = new JsonObject();
        o.addProperty("name", displayName(t));
        o.addProperty("englishName", t.nameEn);
        o.addProperty("registerName", t.id.toString());
        o.addProperty("type", t.stack.getItem() instanceof ItemBlock ? "Block" : "Item");
        o.addProperty("maxStacksSize", t.stack.getMaxStackSize());
        o.addProperty("maxDurability", t.stack.getMaxDamage());
        o.addProperty("CreativeTabName", t.creativeTab);
        o.addProperty("OredictList", t.tags);
        o.addProperty("smallIcon", base64(t, 32, includeIcons));
        o.addProperty("largeIcon", base64(t, 128, includeIcons));
        return o;
    }

    private static JsonObject entityJson(Target t, boolean includeIcons) {
        JsonObject o = new JsonObject();
        o.addProperty("name", displayName(t));
        o.addProperty("Englishname", t.nameEn);
        o.addProperty("mod", t.namespace());
        o.addProperty("registerName", t.id.toString());
        o.addProperty("Icon", base64(t, 128, includeIcons));
        o.addProperty("IconSmall", base64(t, 32, includeIcons));
        o.addProperty("iconSource", t.icons.isEmpty() ? "" : "render");
        o.addProperty("noVisual", t.noVisual);
        o.addProperty("suspect", t.suspect);
        if (t.suspect && !t.suspectReason.isEmpty()) {
            o.addProperty("suspectReason", t.suspectReason);
        }
        if (!t.renderer.isEmpty()) {
            o.addProperty("renderer", t.renderer);
        }
        return o;
    }

    private static JsonObject effectJson(Target t, boolean includeIcons, String neutralAs) {
        JsonObject o = new JsonObject();
        o.addProperty("name", displayName(t));
        o.addProperty("Englishname", t.nameEn);
        o.addProperty("mod", t.namespace());
        o.addProperty("registerName", t.id.toString());
        String category;
        if ("BENEFICIAL".equals(t.effectCategory)) {
            category = "BUFF";
        } else if ("HARMFUL".equals(t.effectCategory)) {
            category = "DEBUFF";
        } else {
            category = "skip".equalsIgnoreCase(neutralAs) ? "BUFF" : neutralAs;
        }
        o.addProperty("category", category);
        o.addProperty("effectCategory", t.effectCategory);
        o.addProperty("color", t.color);
        o.addProperty("instant", t.instant);
        o.addProperty("Icon", base64(t, 128, includeIcons));
        o.addProperty("IconSmall", base64(t, 32, includeIcons));
        o.addProperty("iconSource", "sprite");
        o.addProperty("iconMode", "nearest");
        int actual = t.icons.containsKey(128) ? ImageUtil.colorCount(t.icons.get(128)) : 0;
        o.addProperty("iconColorCount", actual);
        o.addProperty("iconExpectedColorCount", t.expectedColorCount);
        o.addProperty("nameSource", !t.nameZh.isEmpty() ? "zh_cn" : (!t.nameEn.isEmpty() ? "en_us" : "none"));
        o.add("warnings", new JsonArray());
        return o;
    }

    /** 优先中文名，缺失时回退英文名，避免只有 en_us 的模组导出空 name。 */
    private static String displayName(Target t) {
        return t.nameZh.isEmpty() ? t.nameEn : t.nameZh;
    }

    private static String base64(Target t, int size, boolean includeIcons) {
        if (!includeIcons) {
            return "";
        }
        java.awt.image.BufferedImage image = t.icons.get(size);
        return image == null ? "" : ImageUtil.base64(image);
    }

    private static void writeJsonl(Path file, List<JsonObject> lines) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (JsonObject line : lines) {
            sb.append(GSON.toJson(line)).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
