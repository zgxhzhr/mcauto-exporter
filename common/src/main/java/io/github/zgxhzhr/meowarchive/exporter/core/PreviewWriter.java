package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.world.item.BlockItem;

import java.io.IOException;
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

    private PreviewWriter() {
    }

    public static void write(Path root, List<Target> targets) throws IOException {
        Map<String, List<Target>> byNamespace = new TreeMap<>();
        for (Target target : targets) {
            byNamespace.computeIfAbsent(target.namespace(), k -> new ArrayList<>()).add(target);
        }
        for (var entry : byNamespace.entrySet()) {
            String ns = entry.getKey();
            List<Target> list = entry.getValue().stream()
                    .sorted(Comparator.comparing((Target t) -> t.kind)
                            .thenComparing(t -> t.id.toString()))
                    .toList();
            Path dir = root.resolve(ns);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("preview.html"), html(ns, list), StandardCharsets.UTF_8);
        }
    }

    private static String html(String ns, List<Target> targets) {
        int items = 0;
        int blocks = 0;
        int entities = 0;
        int effects = 0;
        int fluids = 0;
        int withIcon = 0;
        int suspects = 0;
        List<String> noVisual = new ArrayList<>();
        StringBuilder cards = new StringBuilder();
        for (Target t : targets) {
            switch (t.kind) {
                case ITEM -> {
                    items++;
                    if (t.stack.getItem() instanceof BlockItem) {
                        blocks++;
                    }
                }
                case ENTITY -> {
                    entities++;
                    if (t.noVisual) {
                        noVisual.add(t.id.toString());
                    }
                }
                case EFFECT -> effects++;
                case FLUID -> {
                    fluids++;
                    if (t.noVisual) {
                        noVisual.add(t.id.toString());
                    }
                }
            }
            if (t.icons.containsKey(128) || t.icons.containsKey(32)) {
                withIcon++;
            }
            if (t.suspect) {
                suspects++;
            }
            cards.append(card(t));
        }
        String fields = """
                <table><tbody>
                <tr><th>物品/方块</th><td>name、englishName、registerName、type、maxStacksSize、maxDurability、CreativeTabName、OredictList、smallIcon、largeIcon、kind</td></tr>
                <tr><th>实体</th><td>name、Englishname、mod、registerName、Icon、IconSmall、iconSource、noVisual、suspect、renderer、kind</td></tr>
                <tr><th>效果</th><td>name、Englishname、mod、registerName、category、effectCategory、color、instant、Icon、IconSmall、iconSource、iconMode、kind</td></tr>
                <tr><th>流体</th><td>name、Englishname、mod、registerName、color、bucket、stillTexture、Icon、IconSmall、iconSource、iconMode、kind</td></tr>
                </tbody></table>""";
        StringBuilder noVisualLine = new StringBuilder();
        if (noVisual.isEmpty()) {
            noVisualLine.append("无");
        } else {
            noVisualLine.append(noVisual.size()).append(" 条：").append(String.join("、", noVisual));
        }
        return TEMPLATE
                .replace("__NS__", esc(ns))
                .replace("__TOTAL__", String.valueOf(targets.size()))
                .replace("__ITEMS__", String.valueOf(items))
                .replace("__BLOCKS__", String.valueOf(blocks))
                .replace("__ENTITIES__", String.valueOf(entities))
                .replace("__EFFECTS__", String.valueOf(effects))
                .replace("__FLUIDS__", String.valueOf(fluids))
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
                : "<img src=\"data:image/" + img.mime() + ";base64," + img.base64() + "\" alt=\"\">";
        String meta = meta(t);
        String search = (t.nameZh + " " + t.nameEn + " " + t.id).toLowerCase(Locale.ROOT);
        return """
                <article class="card" data-k="__KIND__" data-s="__SEARCH__">
                <div class="top">__THUMB__<div>
                <div class="name">__NAME__</div>
                <div class="sub">__EN__</div>
                __BADGES__
                <div class="meta">__META__</div>
                </div></div>
                <details><summary>原始 JSON</summary><pre class="raw">__RAW__</pre></details>
                </article>"""
                .replace("__KIND__", kind)
                .replace("__SEARCH__", esc(search))
                .replace("__THUMB__", thumb)
                .replace("__NAME__", esc(displayName(t)))
                .replace("__EN__", esc(t.nameEn))
                .replace("__BADGES__", badges.toString())
                .replace("__META__", esc(meta))
                .replace("__RAW__", esc(rawJson(t, kind)));
    }

    private static String meta(Target t) {
        return switch (t.kind) {
            case ITEM -> String.format("%s · %s · 堆叠 %d · 耐久 %d · 创造栏 %s",
                    t.stack.getItem() instanceof BlockItem ? "Block" : "Item",
                    t.id, t.stack.getMaxStackSize(), t.stack.getMaxDamage(),
                    t.creativeTab.isEmpty() ? "未知" : t.creativeTab);
            case ENTITY -> String.format("Entity · %s%s", t.id,
                    t.renderer.isEmpty() ? "" : " · 渲染器 " + t.renderer);
            case EFFECT -> String.format("Effect · %s · %s%s", t.id,
                    t.effectCategory, t.instant ? " · 瞬间" : "");
            case FLUID -> String.format("Fluid · %s · %s%s", t.id,
                    t.fluidColor < 0 ? "颜色未知" : String.format("颜色 #%06X", t.fluidColor & 0xFFFFFF),
                    t.bucket.isEmpty() ? "" : " · 容器 "
                            + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.bucket.getItem()));
        };
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
            case ITEM -> {
                o.addProperty("type", t.stack.getItem() instanceof BlockItem ? "Block" : "Item");
                o.addProperty("maxStacksSize", t.stack.getMaxStackSize());
                o.addProperty("maxDurability", t.stack.getMaxDamage());
                o.addProperty("CreativeTabName", t.creativeTab);
                o.addProperty("OredictList", t.tags);
                o.addProperty("smallIcon", foldedItemIcon(t, 32));
                o.addProperty("largeIcon", foldedItemIcon(t, 128));
            }
            case ENTITY -> {
                o.addProperty("Icon", folded(t, 128));
                o.addProperty("IconSmall", folded(t, 32));
                o.addProperty("iconSource", t.icons.isEmpty() ? "" : "render");
                o.addProperty("noVisual", t.noVisual);
                o.addProperty("suspect", t.suspect);
                if (!t.renderer.isEmpty()) {
                    o.addProperty("renderer", t.renderer);
                }
            }
            case EFFECT -> {
                o.addProperty("category", switch (t.effectCategory) {
                    case "BENEFICIAL" -> "BUFF";
                    case "HARMFUL" -> "DEBUFF";
                    default -> "BUFF";
                });
                o.addProperty("effectCategory", t.effectCategory);
                o.addProperty("color", t.color);
                o.addProperty("instant", t.instant);
                o.addProperty("Icon", folded(t, 128));
                o.addProperty("IconSmall", folded(t, 32));
                o.addProperty("iconSource", "sprite");
                o.addProperty("iconMode", "nearest");
            }
            case FLUID -> {
                o.addProperty("color", t.fluidColor);
                o.addProperty("bucket", t.bucket.isEmpty() ? ""
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.bucket.getItem()).toString());
                o.addProperty("stillTexture", t.stillTexture == null ? "" : t.stillTexture.toString());
                o.addProperty("flowingTexture", t.flowingTexture == null ? "" : t.flowingTexture.toString());
                o.addProperty("Icon", folded(t, 128));
                o.addProperty("IconSmall", folded(t, 32));
                o.addProperty("iconSource", t.icons.isEmpty() ? "" : t.fluidIconSource);
                o.addProperty("iconMode", "nearest");
                o.addProperty("IconStill", foldedStill(t, 128));
                o.addProperty("IconStillSmall", foldedStill(t, 32));
                o.addProperty("stillIconSource", t.stillIcons.isEmpty() ? "" : t.fluidStillIconSource);
            }
        }
        o.addProperty("kind", kind);
        return PRETTY.toJson(o);
    }

    private static String folded(Target t, int size) {
        var image = t.icons.get(size);
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

    private static String foldedStill(Target t, int size) {
        var image = t.stillIcons.get(size);
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

    /** 图标：GIF 优先（128 → 32），否则回退 PNG。 */
    private static Icon icon(Target t) {
        byte[] gif = t.animatedGifs.get(128);
        if (gif == null) {
            gif = t.animatedGifs.get(32);
        }
        if (gif != null) {
            return new Icon("gif", ImageUtil.base64Gif(gif));
        }
        var image = t.icons.get(128);
        if (image == null) {
            image = t.icons.get(32);
        }
        return image == null ? null : new Icon("png", ImageUtil.base64(image));
    }

    private record Icon(String mime, String base64) {
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String TEMPLATE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>__NS__ · 交付预览</title><style>
            :root{--bg:#101010;--panel:#211c17;--border:#6d4a2a;--accent:#d0a060;--dim:#9a8b7a}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:#e8e0d5;font:14px/1.7 system-ui,"Microsoft YaHei",sans-serif}
            header{position:sticky;top:0;background:#171310;border-bottom:1px solid var(--border);padding:14px 20px;z-index:2}
            header h1{margin:0;font-size:18px;color:var(--accent)}
            header .info{color:var(--dim);font-size:12px;margin-top:4px}
            .bar{display:flex;gap:8px;align-items:center;margin-top:10px;flex-wrap:wrap}
            .tab{padding:4px 12px;border:1px solid var(--border);border-radius:6px;background:#1b150f;
              color:var(--dim);cursor:pointer;font-size:12px}
            .tab.active{color:#1b140d;background:var(--accent);border-color:var(--accent)}
            input[type=search]{margin-left:auto;min-width:240px;padding:6px 10px;border-radius:6px;
              border:1px solid #4b3521;background:#151210;color:#e8e0d5;outline:none;font-size:12px}
            input[type=search]:focus{border-color:var(--accent)}
            main{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:16px;padding:20px}
            .card{background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:14px;overflow:hidden}
            .card.intro{grid-column:1/-1}
            .top{display:flex;gap:14px;align-items:flex-start}
            .top img{width:96px;height:96px;image-rendering:pixelated;background:#151210;
              border:1px solid #3a2a1c;border-radius:8px}
            .noicon{width:96px;height:96px;display:flex;align-items:center;justify-content:center;
              color:var(--dim);border:1px dashed #3a2a1c;border-radius:8px;font-size:12px;flex:0 0 96px}
            .name{font-size:17px;font-weight:600;color:#ffe082}
            .sub{color:var(--dim);font-size:12px;min-height:16px}
            .meta{color:#b9a78f;font-size:12px;margin-top:4px;word-break:break-all}
            .badge{display:inline-block;font-size:11px;color:#1b140d;background:var(--accent);
              border-radius:4px;padding:1px 7px;margin-bottom:6px}
            .badge.warn{background:#a8574a;color:#ffe9e4;margin-left:4px}
            h2,h3{color:#ffd08a;margin:16px 0 6px}
            h2{font-size:19px}h3{font-size:16px}
            table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px}
            th,td{border:1px solid #4b3521;padding:5px 8px;text-align:left}th{background:#2e241a;color:#ffd08a}
            details{margin-top:8px;border-top:1px dashed #3a2a1c;padding-top:6px}
            summary{cursor:pointer;color:var(--dim);font-size:12px}
            pre.raw{margin:6px 0 0;padding:10px;background:#151210;border:1px solid #3a2a1c;border-radius:6px;
              color:#c9bda8;font:11px/1.5 Consolas,monospace;white-space:pre-wrap;word-break:break-all;
              max-height:340px;overflow:auto}
            .kv{display:grid;grid-template-columns:88px 1fr;gap:2px 8px;font-size:12px;margin-top:6px}
            .kv dt{color:var(--dim)}.kv dd{margin:0;color:#d8cbb5;word-break:break-all}
            .empty{color:#7d6f60;font-style:italic;grid-column:1/-1;padding:40px;text-align:center}
            </style></head><body>
            <header><h1>__NS__ · 交付预览</h1>
            <div class="info">__TOTAL__ 条资料 · 图标 __WITHICON__/__TOTAL__ · __NS___all.json</div>
            <div class="bar">
            <button class="tab active" data-k="all">全部</button>
            <button class="tab" data-k="item">物品 / 方块</button>
            <button class="tab" data-k="entity">实体</button>
            <button class="tab" data-k="effect">效果</button>
            <button class="tab" data-k="fluid">流体</button>
            <input type="search" id="q" placeholder="搜索名称 / 英文 / 注册名…">
            </div></header>
            <main id="grid">
            <article class="card intro"><div class="badge">导出概览</div>
            <h2>__NS__</h2>
            <dl class="kv">
            <dt>条目</dt><dd>__ITEMS__ 物品/方块（__BLOCKS__ 方块） + __ENTITIES__ 实体 + __EFFECTS__ 效果 + __FLUIDS__ 流体，共 __TOTAL__</dd>
            <dt>图标</dt><dd>__WITHICON__/__TOTAL__</dd>
            <dt>可疑</dt><dd>__SUSPECTS__</dd>
            <dt>无视觉</dt><dd>__NOVISUAL__</dd>
            </dl>
            <h3>字段</h3>
            __FIELDS__
            </article>
            __CARDS__
            </main>
            <script>
            const grid=document.getElementById('grid'),q=document.getElementById('q');
            let kind='all';
            function apply(){
              const kw=q.value.trim().toLowerCase();
              grid.querySelectorAll('.card:not(.intro)').forEach(c=>{
                const okKind = kind==='all'||c.dataset.k===kind;
                const okKw = !kw||c.dataset.s.includes(kw);
                c.style.display = okKind&&okKw ? '' : 'none';
              });
            }
            document.querySelectorAll('.tab').forEach(t=>t.addEventListener('click',()=>{
              document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));
              t.classList.add('active'); kind=t.dataset.k; apply();
            }));
            q.addEventListener('input',apply);
            </script>
            </body></html>
            """;
}
