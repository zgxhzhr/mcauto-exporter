package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.item.ItemBlock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 为每个命名空间生成单文件预览页 &lt;ns&gt;/preview.html。
 * 图标以 base64 内嵌，每条资料附折叠的原始 JSON（base64 折叠为长度说明），
 * 支持按类型筛选与关键字搜索。
 */
public final class PreviewWriter {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static String template;

    private static final String FIELDS =
            "<table><tbody>\n"
                    + "<tr><th>物品/方块</th><td>name、englishName、registerName、metadata、type、maxStacksSize、"
                    + "maxStackSize、maxDurability、CreativeTabName、OredictList、smallIcon、largeIcon、kind</td></tr>\n"
                    + "<tr><th>实体</th><td>name、Englishname、mod、registerName、Icon、IconSmall、iconSource、"
                    + "noVisual、suspect、renderer、kind</td></tr>\n"
                    + "<tr><th>效果</th><td>name、Englishname、mod、registerName、category、effectCategory、color、"
                    + "instant、Icon、IconSmall、iconSource、iconMode、kind</td></tr>\n"
                    + "</tbody></table>";

    private PreviewWriter() {
    }

    public static void write(Path root, List<Target> targets) throws IOException {
        Map<String, List<Target>> byNamespace = new TreeMap<>();
        for (Target target : targets) {
            List<Target> list = byNamespace.get(target.namespace());
            if (list == null) {
                list = new ArrayList<>();
                byNamespace.put(target.namespace(), list);
            }
            list.add(target);
        }
        for (Map.Entry<String, List<Target>> entry : byNamespace.entrySet()) {
            String ns = entry.getKey();
            List<Target> list = new ArrayList<>(entry.getValue());
            Collections.sort(list, new Comparator<Target>() {
                @Override
                public int compare(Target a, Target b) {
                    int kind = a.kind.compareTo(b.kind);
                    if (kind != 0) {
                        return kind;
                    }
                    int id = a.id.toString().compareTo(b.id.toString());
                    if (id != 0) {
                        return id;
                    }
                    int ma = a.stack == null ? 0 : a.stack.getDamage();
                    int mb = b.stack == null ? 0 : b.stack.getDamage();
                    return Integer.compare(ma, mb);
                }
            });
            Path dir = root.resolve(ns);
            Files.createDirectories(dir);
            Files.write(dir.resolve("preview.html"), html(ns, list).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String html(String ns, List<Target> targets) {
        int items = 0;
        int blocks = 0;
        int entities = 0;
        int effects = 0;
        int withIcon = 0;
        int suspects = 0;
        List<String> noVisual = new ArrayList<>();
        StringBuilder cards = new StringBuilder();
        for (Target t : targets) {
            if (t.kind == Target.Kind.ITEM) {
                items++;
                if (t.stack.getItem() instanceof ItemBlock) {
                    blocks++;
                }
            } else if (t.kind == Target.Kind.ENTITY) {
                entities++;
                if (t.noVisual) {
                    noVisual.add(t.id.toString());
                }
            } else {
                effects++;
            }
            if (t.icons.containsKey(128) || t.icons.containsKey(32)) {
                withIcon++;
            }
            if (t.suspect) {
                suspects++;
            }
            cards.append(card(t));
        }
        StringBuilder noVisualLine = new StringBuilder();
        if (noVisual.isEmpty()) {
            noVisualLine.append("无");
        } else {
            noVisualLine.append(noVisual.size()).append(" 条：");
            for (int i = 0; i < noVisual.size(); i++) {
                if (i > 0) {
                    noVisualLine.append("、");
                }
                noVisualLine.append(noVisual.get(i));
            }
        }
        return template()
                .replace("__NS__", esc(ns))
                .replace("__TOTAL__", String.valueOf(targets.size()))
                .replace("__ITEMS__", String.valueOf(items))
                .replace("__BLOCKS__", String.valueOf(blocks))
                .replace("__ENTITIES__", String.valueOf(entities))
                .replace("__EFFECTS__", String.valueOf(effects))
                .replace("__WITHICON__", String.valueOf(withIcon))
                .replace("__SUSPECTS__", String.valueOf(suspects))
                .replace("__NOVISUAL__", esc(noVisualLine.toString()))
                .replace("__FIELDS__", FIELDS)
                .replace("__CARDS__", cards.toString());
    }

    private static String card(Target t) {
        String kind = t.kind.name().toLowerCase(Locale.ROOT);
        String img = icon(t);
        StringBuilder badges = new StringBuilder();
        if (t.kind == Target.Kind.ENTITY && t.noVisual) {
            badges.append("<span class=\"badge warn\">无可见模型</span>");
        }
        if (t.suspect) {
            badges.append("<span class=\"badge warn\">可疑</span>");
        }
        String thumb = img.isEmpty()
                ? "<div class=\"noicon\">无图标</div>"
                : "<img src=\"data:image/png;base64," + img + "\" alt=\"\">";
        String meta = meta(t);
        String search = (t.nameZh + " " + t.nameEn + " " + t.id).toLowerCase(Locale.ROOT);
        // 先把整段卡片拼成一个字符串再替换，否则链式 replace 只会作用在最后一个字面量上
        String card = "<article class=\"card\" data-k=\"__KIND__\" data-s=\"__SEARCH__\">\n"
                + "<div class=\"top\">__THUMB__<div>\n"
                + "<div class=\"name\">__NAME__</div>\n"
                + "<div class=\"sub\">__EN__</div>\n"
                + "__BADGES__\n"
                + "<div class=\"meta\">__META__</div>\n"
                + "</div></div>\n"
                + "<details><summary>原始 JSON</summary><pre class=\"raw\">__RAW__</pre></details>\n"
                + "</article>";
        return card.replace("__KIND__", kind)
                .replace("__SEARCH__", esc(search))
                .replace("__THUMB__", thumb)
                .replace("__NAME__", esc(displayName(t)))
                .replace("__EN__", esc(t.nameEn))
                .replace("__BADGES__", badges.toString())
                .replace("__META__", esc(meta))
                .replace("__RAW__", esc(rawJson(t, kind)));
    }

    private static String meta(Target t) {
        if (t.kind == Target.Kind.ITEM) {
            int metadata = t.stack.getDamage();
            return String.format("%s · %s%s · 堆叠 %d · 耐久 %d · 创造栏 %s",
                    t.stack.getItem() instanceof ItemBlock ? "Block" : "Item",
                    t.id, metadata == 0 ? "" : " · 元数据 " + metadata,
                    t.stack.getMaxStackSize(), t.stack.getMaxDamage(),
                    t.creativeTab.isEmpty() ? "未知" : t.creativeTab);
        }
        if (t.kind == Target.Kind.ENTITY) {
            return String.format("Entity · %s%s", t.id,
                    t.renderer.isEmpty() ? "" : " · 渲染器 " + t.renderer);
        }
        return String.format("Effect · %s · %s%s", t.id,
                t.effectCategory, t.instant ? " · 瞬间" : "");
    }

    /** 与 LMS 输出同构的原始 JSON，图标字段折叠为长度说明。 */
    private static String rawJson(Target t, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("name", displayName(t));
        if (t.kind == Target.Kind.ITEM) {
            o.addProperty("englishName", t.nameEn);
        } else {
            o.addProperty("Englishname", t.nameEn);
            o.addProperty("mod", t.namespace());
        }
        o.addProperty("registerName", t.id.toString());
        if (t.kind == Target.Kind.ITEM) {
            o.addProperty("metadata", t.stack.getDamage());
            o.addProperty("type", t.stack.getItem() instanceof ItemBlock ? "Block" : "Item");
            o.addProperty("maxStacksSize", t.stack.getMaxStackSize());
            o.addProperty("maxStackSize", t.stack.getMaxStackSize());
            o.addProperty("maxDurability", t.stack.getMaxDamage());
            o.addProperty("CreativeTabName", t.creativeTab);
            o.addProperty("OredictList", t.tags);
            o.addProperty("smallIcon", folded(t, 32));
            o.addProperty("largeIcon", folded(t, 128));
        } else if (t.kind == Target.Kind.ENTITY) {
            o.addProperty("Icon", folded(t, 128));
            o.addProperty("IconSmall", folded(t, 32));
            o.addProperty("iconSource", t.icons.isEmpty() ? "" : "render");
            o.addProperty("noVisual", t.noVisual);
            o.addProperty("suspect", t.suspect);
            if (!t.renderer.isEmpty()) {
                o.addProperty("renderer", t.renderer);
            }
        } else {
            String category;
            if ("BENEFICIAL".equals(t.effectCategory)) {
                category = "BUFF";
            } else if ("HARMFUL".equals(t.effectCategory)) {
                category = "DEBUFF";
            } else {
                category = "BUFF";
            }
            o.addProperty("category", category);
            o.addProperty("effectCategory", t.effectCategory);
            o.addProperty("color", t.color);
            o.addProperty("instant", t.instant);
            o.addProperty("Icon", folded(t, 128));
            o.addProperty("IconSmall", folded(t, 32));
            o.addProperty("iconSource", "sprite");
            o.addProperty("iconMode", "nearest");
        }
        o.addProperty("kind", kind);
        return PRETTY.toJson(o);
    }

    private static String folded(Target t, int size) {
        java.awt.image.BufferedImage image = t.icons.get(size);
        if (image == null) {
            return "";
        }
        return "<base64 " + ImageUtil.base64(image).length() + " chars>";
    }

    private static String displayName(Target t) {
        if (!t.nameZh.isEmpty()) {
            return t.nameZh;
        }
        return t.nameEn.isEmpty() ? t.id.toString() : t.nameEn;
    }

    private static String icon(Target t) {
        java.awt.image.BufferedImage image = t.icons.get(128);
        if (image == null) {
            image = t.icons.get(32);
        }
        return image == null ? "" : ImageUtil.base64(image);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String template() {
        if (template != null) {
            return template;
        }
        InputStream in = PreviewWriter.class.getResourceAsStream("/assets/meowarchive/preview-template.html");
        if (in == null) {
            ExporterLog.LOG.warn("缺少预览模板资源，使用空页面");
            template = "<!doctype html><html><body>__NS__ __CARDS__</body></html>";
            return template;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            template = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ExporterLog.LOG.warn("读取预览模板失败: {}", e.toString());
            template = "<!doctype html><html><body>__NS__ __CARDS__</body></html>";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return template;
    }
}
