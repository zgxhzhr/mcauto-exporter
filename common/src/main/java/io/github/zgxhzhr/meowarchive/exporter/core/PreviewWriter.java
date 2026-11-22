package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.JsonArray;
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
 * 为每个命名空间生成单文件预览页 &lt;ns&gt;/preview.html：图标以 base64 内嵌，
 * 支持按类型筛选与关键字搜索，点击卡片复制注册名。
 */
public final class PreviewWriter {
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
        JsonArray cards = new JsonArray();
        int items = 0;
        int entities = 0;
        int effects = 0;
        for (Target t : targets) {
            switch (t.kind) {
                case ITEM -> items++;
                case ENTITY -> entities++;
                case EFFECT -> effects++;
            }
            JsonObject card = new JsonObject();
            String kind = t.kind.name().toLowerCase(Locale.ROOT);
            card.addProperty("kind", kind);
            card.addProperty("kindLabel", kindLabel(t));
            card.addProperty("name", displayName(t));
            card.addProperty("en", t.nameEn);
            card.addProperty("reg", t.id.toString());
            card.addProperty("img", icon(t));
            JsonArray tags = new JsonArray();
            if (t.kind == Target.Kind.ITEM) {
                if (!t.creativeTab.isEmpty() && !"未知".equals(t.creativeTab)) {
                    tags.add(t.creativeTab);
                }
            } else if (t.kind == Target.Kind.ENTITY) {
                if (t.noVisual) {
                    tags.add("无可见模型");
                }
                if (t.suspect) {
                    tags.add("可疑");
                }
            } else {
                tags.add(t.effectCategory);
                if (t.instant) {
                    tags.add("瞬间");
                }
            }
            card.add("tags", tags);
            cards.add(card);
        }
        return TEMPLATE
                .replace("__NS__", ns)
                .replace("__TOTAL__", String.valueOf(targets.size()))
                .replace("__ITEM__", String.valueOf(items))
                .replace("__ENT__", String.valueOf(entities))
                .replace("__EFF__", String.valueOf(effects))
                .replace("__DATA__", cards.toString());
    }

    private static String kindLabel(Target t) {
        return switch (t.kind) {
            case ITEM -> t.stack.getItem() instanceof BlockItem ? "方块" : "物品";
            case ENTITY -> "实体";
            case EFFECT -> "效果";
        };
    }

    private static String displayName(Target t) {
        if (!t.nameZh.isEmpty()) {
            return t.nameZh;
        }
        return t.nameEn.isEmpty() ? t.id.toString() : t.nameEn;
    }

    private static String icon(Target t) {
        var image = t.icons.get(128);
        if (image == null) {
            image = t.icons.get(32);
        }
        return image == null ? "" : ImageUtil.base64(image);
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>__NS__ 导出预览</title>
            <style>
              :root{--bg:#0f1115;--panel:#171a21;--panel2:#1d212a;--line:#2a3040;
                --text:#e6e9ef;--dim:#8b93a7;--accent:#5aa9ff;--ok:#3ecf8e;--warn:#ffb454}
              *{box-sizing:border-box}
              body{margin:0;background:var(--bg);color:var(--text);
                font:14px/1.5 "Segoe UI",-apple-system,"Microsoft YaHei",sans-serif}
              header{position:sticky;top:0;z-index:10;background:rgba(15,17,21,.92);
                backdrop-filter:blur(8px);border-bottom:1px solid var(--line);padding:16px 24px}
              h1{margin:0 0 4px;font-size:20px;font-weight:600}
              .sub{color:var(--dim);font-size:12px}
              .stats{display:flex;gap:20px;margin-top:10px;flex-wrap:wrap}
              .stat b{color:var(--accent);font-size:16px;margin-right:4px}
              .stat span{color:var(--dim);font-size:12px}
              .bar{display:flex;gap:8px;align-items:center;margin-top:14px;flex-wrap:wrap}
              .tab{padding:6px 14px;border:1px solid var(--line);border-radius:999px;
                background:var(--panel);color:var(--dim);cursor:pointer;font-size:13px}
              .tab.active{color:var(--text);border-color:var(--accent);background:#16233a}
              input[type=search]{margin-left:auto;min-width:240px;padding:7px 12px;border-radius:8px;
                border:1px solid var(--line);background:var(--panel);color:var(--text);outline:none}
              input[type=search]:focus{border-color:var(--accent)}
              main{padding:20px 24px 60px;display:grid;gap:14px;
                grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}
              .card{background:var(--panel);border:1px solid var(--line);border-radius:12px;
                padding:14px;display:flex;gap:14px;align-items:center;
                transition:border-color .15s,transform .15s;cursor:pointer}
              .card:hover{border-color:var(--accent);transform:translateY(-2px)}
              .thumb{width:72px;height:72px;flex:0 0 72px;display:flex;align-items:center;
                justify-content:center;background:var(--panel2);border-radius:10px;
                image-rendering:pixelated}
              .thumb img{max-width:64px;max-height:64px;image-rendering:pixelated}
              .thumb.empty{color:#444c60;font-size:11px}
              .info{min-width:0;flex:1}
              .nm{font-weight:600;font-size:14px;white-space:nowrap;overflow:hidden;
                text-overflow:ellipsis}
              .en{color:var(--dim);font-size:12px;white-space:nowrap;overflow:hidden;
                text-overflow:ellipsis;margin-bottom:4px}
              .reg{color:#6f7d99;font:11px/1.4 ui-monospace,Consolas,monospace;
                white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
              .tags{display:flex;gap:6px;flex-wrap:wrap;margin-top:6px}
              .tag{font-size:11px;padding:1px 7px;border-radius:999px;background:#20283a;
                color:#9fb4d8;border:1px solid #2c3852}
              .kind{font-size:11px;padding:1px 7px;border-radius:999px}
              .kind.item{background:#123126;color:var(--ok);border:1px solid #1e5540}
              .kind.entity{background:#122a3d;color:var(--accent);border:1px solid #1d4a6b}
              .kind.effect{background:#33260f;color:var(--warn);border:1px solid #5a431a}
              .empty-state{grid-column:1/-1;text-align:center;color:var(--dim);padding:60px}
              footer{position:fixed;right:14px;bottom:10px;color:#3c4457;font-size:11px}
            </style>
            </head>
            <body>
            <header>
              <h1>__NS__ 导出预览</h1>
              <div class="sub">Meow Archive Exporter 产物预览</div>
              <div class="stats">
                <div class="stat"><b>__TOTAL__</b><span>条目</span></div>
                <div class="stat"><b>__ENT__</b><span>实体</span></div>
                <div class="stat"><b>__ITEM__</b><span>物品/方块</span></div>
                <div class="stat"><b>__EFF__</b><span>效果</span></div>
              </div>
              <div class="bar">
                <button class="tab active" data-k="all">全部</button>
                <button class="tab" data-k="entity">实体</button>
                <button class="tab" data-k="item">物品 / 方块</button>
                <button class="tab" data-k="effect">效果</button>
                <input type="search" id="q" placeholder="搜索名称 / 英文 / 注册名…">
              </div>
            </header>
            <main id="grid"></main>
            <footer>点击卡片复制注册名</footer>
            <script>
            const DATA = __DATA__;
            const grid = document.getElementById('grid');
            const q = document.getElementById('q');
            let kind = 'all';

            function esc(s){return String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}

            function render(){
              const kw = q.value.trim().toLowerCase();
              const list = DATA.filter(d=>{
                if(kind!=='all' && d.kind!==kind) return false;
                if(!kw) return true;
                return (d.name+' '+d.en+' '+d.reg).toLowerCase().includes(kw);
              });
              if(!list.length){grid.innerHTML='<div class="empty-state">没有匹配的条目</div>';return}
              grid.innerHTML = list.map(d=>`
                <div class="card" data-reg="${esc(d.reg)}" title="点击复制 ${esc(d.reg)}">
                  <div class="thumb ${d.img?'':'empty'}">${d.img?`<img src="data:image/png;base64,${d.img}" alt="">`:'无图'}</div>
                  <div class="info">
                    <div class="nm">${esc(d.name||d.en||d.reg)}</div>
                    <div class="en">${esc(d.en)}</div>
                    <div class="reg">${esc(d.reg)}</div>
                    <div class="tags">
                      <span class="kind ${d.kind}">${d.kindLabel}</span>
                      ${d.tags.map(t=>`<span class="tag">${esc(t)}</span>`).join('')}
                    </div>
                  </div>
                </div>`).join('');
            }

            grid.addEventListener('click', async ev=>{
              const card = ev.target.closest('.card');
              if(!card) return;
              try{ await navigator.clipboard.writeText(card.dataset.reg); }catch(e){}
              const old = card.style.borderColor;
              card.style.borderColor='#3ecf8e';
              setTimeout(()=>card.style.borderColor=old, 400);
            });

            document.querySelectorAll('.tab').forEach(t=>t.addEventListener('click',()=>{
              document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));
              t.classList.add('active');
              kind = t.dataset.k;
              render();
            }));
            q.addEventListener('input', render);
            render();
            </script>
            </body>
            </html>
            """;
}
