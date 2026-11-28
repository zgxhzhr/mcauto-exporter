package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.world.item.BlockItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按命名空间分目录输出 LMS 兼容 JSONL：&lt;ns&gt;/&lt;ns&gt;_items.json、&lt;ns&gt;/&lt;ns&gt;_entities.json。
 * 效果为本仓库扩展，写入 &lt;ns&gt;/&lt;ns&gt;_effects.json；另输出合并文件 &lt;ns&gt;/&lt;ns&gt;_all.json，
 * 包含该命名空间下全部条目，每条附加 kind 字段（item / entity / effect）。
 */
public final class LmsWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private LmsWriter() {
    }

    public static void write(Path root, List<Target> targets, boolean includeIcons, String neutralAs) throws IOException {
        Map<String, List<Target>> items = new LinkedHashMap<>();
        Map<String, List<Target>> entities = new LinkedHashMap<>();
        Map<String, List<Target>> effects = new LinkedHashMap<>();
        for (Target target : targets) {
            switch (target.kind) {
                case ITEM -> items.computeIfAbsent(target.namespace(), k -> new ArrayList<>()).add(target);
                case ENTITY -> entities.computeIfAbsent(target.namespace(), k -> new ArrayList<>()).add(target);
                case EFFECT -> effects.computeIfAbsent(target.namespace(), k -> new ArrayList<>()).add(target);
            }
        }
        for (var entry : items.entrySet()) {
            List<JsonObject> lines = new ArrayList<>();
            entry.getValue().stream()
                    .sorted(Comparator.comparing(t -> t.id.toString()))
                    .forEach(t -> lines.add(itemJson(t, includeIcons)));
            writeJsonl(root.resolve(entry.getKey()).resolve(entry.getKey() + "_items.json"), lines);
        }
        for (var entry : entities.entrySet()) {
            List<JsonObject> lines = new ArrayList<>();
            entry.getValue().stream()
                    .sorted(Comparator.comparing(t -> t.id.toString()))
                    .forEach(t -> lines.add(entityJson(t, includeIcons)));
            writeJsonl(root.resolve(entry.getKey()).resolve(entry.getKey() + "_entities.json"), lines);
        }
        for (var entry : effects.entrySet()) {
            List<JsonObject> lines = new ArrayList<>();
            entry.getValue().stream()
                    .sorted(Comparator.comparing(t -> t.id.toString()))
                    .forEach(t -> lines.add(effectJson(t, includeIcons, neutralAs)));
            writeJsonl(root.resolve(entry.getKey()).resolve(entry.getKey() + "_effects.json"), lines);
        }
        // 合并文件：物品 → 实体 → 效果依次排列，每条补 kind 字段
        var namespaces = new java.util.TreeSet<String>();
        namespaces.addAll(items.keySet());
        namespaces.addAll(entities.keySet());
        namespaces.addAll(effects.keySet());
        for (String ns : namespaces) {
            List<JsonObject> lines = new ArrayList<>();
            for (Map<String, List<Target>> group : List.of(items, entities, effects)) {
                group.getOrDefault(ns, List.of()).stream()
                        .sorted(Comparator.comparing(t -> t.id.toString()))
                        .forEach(t -> lines.add(mergedJson(t, includeIcons, neutralAs)));
            }
            writeJsonl(root.resolve(ns).resolve(ns + "_all.json"), lines);
        }
    }

    private static JsonObject mergedJson(Target t, boolean includeIcons, String neutralAs) {
        JsonObject o = switch (t.kind) {
            case ITEM -> itemJson(t, includeIcons);
            case ENTITY -> entityJson(t, includeIcons);
            case EFFECT -> effectJson(t, includeIcons, neutralAs);
        };
        o.addProperty("kind", t.kind.name().toLowerCase(java.util.Locale.ROOT));
        return o;
    }

    private static JsonObject itemJson(Target t, boolean includeIcons) {
        JsonObject o = new JsonObject();
        o.addProperty("name", displayName(t));
        o.addProperty("englishName", t.nameEn);
        o.addProperty("registerName", t.id.toString());
        o.addProperty("type", t.stack.getItem() instanceof BlockItem ? "Block" : "Item");
        o.addProperty("maxStacksSize", t.stack.getMaxStackSize());
        o.addProperty("maxDurability", t.stack.getMaxDamage());
        o.addProperty("CreativeTabName", t.creativeTab);
        o.addProperty("OredictList", t.tags);
        o.addProperty("smallIcon", itemIcon(t, 32, includeIcons));
        o.addProperty("largeIcon", itemIcon(t, 128, includeIcons));
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
        String category = switch (t.effectCategory) {
            case "BENEFICIAL" -> "BUFF";
            case "HARMFUL" -> "DEBUFF";
            default -> "skip".equalsIgnoreCase(neutralAs) ? "BUFF" : neutralAs;
        };
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

    /** 物品图标：动画物品优先输出 GIF 裸 base64，其余维持 PNG。 */
    private static String itemIcon(Target t, int size, boolean includeIcons) {
        if (!includeIcons) {
            return "";
        }
        byte[] gif = t.animatedGifs.get(size);
        if (gif != null) {
            return ImageUtil.base64Gif(gif);
        }
        var image = t.icons.get(size);
        return image == null ? "" : ImageUtil.base64(image);
    }

    private static String base64(Target t, int size, boolean includeIcons) {
        if (!includeIcons) {
            return "";
        }
        var image = t.icons.get(size);
        return image == null ? "" : ImageUtil.base64(image);
    }

    private static void writeJsonl(Path file, List<JsonObject> lines) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (JsonObject line : lines) {
            sb.append(GSON.toJson(line)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
