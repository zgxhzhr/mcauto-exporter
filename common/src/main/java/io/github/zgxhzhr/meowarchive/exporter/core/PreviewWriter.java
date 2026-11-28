package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.world.item.BlockItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private PreviewWriter() {
    }

    public static void write(Path root, List<Target> targets) throws IOException {
        Map<String, List<Target>> byNamespace = new TreeMap<>();
        for (Target target : targets) {
            byNamespace.computeIfAbsent(target.namespace(), k -> new ArrayList<>()).add(target);
        }
        for (Map.Entry<String, List<Target>> entry : byNamespace.entrySet()) {
            String ns = entry.getKey();
            List<Target> list = new ArrayList<>(entry.getValue());
            list.sort(Comparator.comparing((Target t) -> t.kind).thenComparing(t -> t.id.toString()));
            Path dir = root.resolve(ns);
            Files.createDirectories(dir);
            Files.write(dir.resolve("preview.html"), html(ns, list).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 模板放在资源包里：老版本（Java 8）不支持文本块，也便于统一维护。 */
    private static String template() {
        if (template == null) {
            try (InputStream in = PreviewWriter.class.getResourceAsStream("/assets/meowarchive/preview-template.html")) {
                if (in == null) {
                    throw new IOException("缺少 preview-template.html");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                template = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return template;
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
            switch (t.kind) {
                case ITEM:
                    items++;
                    if (t.stack.getItem() instanceof BlockItem) {
                        blocks++;
                    }
                    break;
                case ENTITY:
                    entities++;
                    if (t.noVisual) {
                        noVisual.add(t.id.toString());
                    }
                    break;
                default:
                    effects++;
                    break;
            }
            if (t.icons.containsKey(128) || t.icons.containsKey(32)) {
                withIcon++;
            }
            if (t.suspect) {
                suspects++;
            }
            cards.append(card(t));
        }
        String fields = "<table><tbody>"
                + "<tr><th>物品/方块</th><td>name、englishName、registerName、type、maxStacksSize、maxDurability、"
                + "CreativeTabName、OredictList、smallIcon、largeIcon、kind</td></tr>"
                + "<tr><th>实体</th><td>name、Englishname、mod、registerName、Icon、IconSmall、iconSource、"
                + "noVisual、suspect、renderer、kind</td></tr>"
                + "<tr><th>效果</th><td>name、Englishname、mod、registerName、category、effectCategory、color、"
                + "instant、Icon、IconSmall、iconSource、iconMode、kind</td></tr>"
                + "</tbody></table>";
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
                .replace("__FIELDS__", fields)
                .replace("__CARDS__", cards.toString());
    }

    private static String card(Target t) {
        String kind = t.kind.name().toLowerCase(Locale.ROOT);
        Icon img = icon(t);
        StringBuilder badges = new StringBuilder();
        if (t.kind == Target.Kind.ENTITY && t.noVisual) {
            badges.append("<span class=\"badge warn\">无可见模型</span>");
        }
        if (t.suspect) {
            badges.append("<span class=\"badge warn\">可疑</span>");
        }
        String thumb = img == null
                ? "<div class=\"noicon\">无图标</div>"
                : "<img src=\"data:image/" + img.mime + ";base64," + img.base64 + "\" alt=\"\">";
        String meta = meta(t);
        String search = (t.nameZh + " " + t.nameEn + " " + t.id).toLowerCase(Locale.ROOT);
        return "<article class=\"card\" data-k=\"" + kind + "\" data-s=\"" + esc(search) + "\">\n"
                + "<div class=\"top\">" + thumb + "<div>\n"
                + "<div class=\"name\">" + esc(displayName(t)) + "</div>\n"
                + "<div class=\"sub\">" + esc(t.nameEn) + "</div>\n"
                + badges + "\n"
                + "<div class=\"meta\">" + esc(meta) + "</div>\n"
                + "</div></div>\n"
                + "<details><summary>原始 JSON</summary><pre class=\"raw\">" + esc(rawJson(t, kind))
                + "</pre></details>\n"
                + "</article>";
    }

    private static String meta(Target t) {
        switch (t.kind) {
            case ITEM:
                return String.format("%s · %s · 堆叠 %d · 耐久 %d · 创造栏 %s",
                        t.stack.getItem() instanceof BlockItem ? "Block" : "Item",
                        t.id, t.stack.getMaxStackSize(), t.stack.getMaxDamage(),
                        t.creativeTab.isEmpty() ? "未知" : t.creativeTab);
            case ENTITY:
                return String.format("Entity · %s%s", t.id,
                        t.renderer.isEmpty() ? "" : " · 渲染器 " + t.renderer);
            default:
                return String.format("MobEffect · %s · %s%s", t.id,
                        t.effectCategory, t.instant ? " · 瞬间" : "");
        }
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
        switch (t.kind) {
            case ITEM:
                o.addProperty("type", t.stack.getItem() instanceof BlockItem ? "Block" : "Item");
                o.addProperty("maxStacksSize", t.stack.getMaxStackSize());
                o.addProperty("maxDurability", t.stack.getMaxDamage());
                o.addProperty("CreativeTabName", t.creativeTab);
                o.addProperty("OredictList", t.tags);
                o.addProperty("smallIcon", foldedItemIcon(t, 32));
                o.addProperty("largeIcon", foldedItemIcon(t, 128));
                break;
            case ENTITY:
                o.addProperty("Icon", folded(t, 128));
                o.addProperty("IconSmall", folded(t, 32));
                o.addProperty("iconSource", t.icons.isEmpty() ? "" : "render");
                o.addProperty("noVisual", t.noVisual);
                o.addProperty("suspect", t.suspect);
                if (!t.renderer.isEmpty()) {
                    o.addProperty("renderer", t.renderer);
                }
                break;
            default:
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
                break;
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

    /** 物品图标字段：有 GIF 时标注 gif 长度，否则沿用 PNG 折叠说明。 */
    private static String foldedItemIcon(Target t, int size) {
        byte[] gif = t.animatedGifs.get(size);
        if (gif != null) {
            return "<gif " + (4 * ((gif.length + 2) / 3)) + " chars>";
        }
        return folded(t, size);
    }

    private static String displayName(Target t) {
        if (!t.nameZh.isEmpty()) {
            return t.nameZh;
        }
        return t.nameEn.isEmpty() ? t.id.toString() : t.nameEn;
    }

    /** 图标：GIF 优先（128 → 32），否则回退 PNG。 */
    private static Icon icon(Target t) {
        byte[] gif = t.animatedGifs.get(128);
        if (gif == null) {
            gif = t.animatedGifs.get(32);
        }
        if (gif != null) {
            return new Icon("gif", ImageUtil.base64Gif(gif));
        }
        java.awt.image.BufferedImage image = t.icons.get(128);
        if (image == null) {
            image = t.icons.get(32);
        }
        return image == null ? null : new Icon("png", ImageUtil.base64(image));
    }

    private static final class Icon {
        final String mime;
        final String base64;

        Icon(String mime, String base64) {
            this.mime = mime;
            this.base64 = base64;
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
