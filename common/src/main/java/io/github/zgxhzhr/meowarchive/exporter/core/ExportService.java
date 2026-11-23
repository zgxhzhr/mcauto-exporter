package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.render.EffectIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import io.github.zgxhzhr.meowarchive.exporter.render.ItemIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.scan.RegistryScanner;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出编排：扫描 → 每帧渲染一个目标 → 落 PNG → 写 LMS JSON。
 * 所有方法都在客户端主线程调用；渲染只在 HUD 阶段执行。
 */
public final class ExportService {
    private static final ExportService INSTANCE = new ExportService();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean running;
    private ExportRequest request;
    private List<Target> targets = new ArrayList<>();
    private int index;
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Map<String, String> noVisualTargets = new LinkedHashMap<>();
    private final Map<String, String> suspectTargets = new LinkedHashMap<>();
    private String error = "";
    private String currentLabel = "";
    private String lastResult = "";
    private final Deque<String> log = new ArrayDeque<>();
    private Path output;
    private long startedAt;

    private ExportService() {
    }

    public static ExportService get() {
        return INSTANCE;
    }

    public synchronized String start(ExportRequest req) {
        if (running) {
            return "已有导出任务在运行";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return "请先进入一个世界";
        }
        if (req.mode == ExportMode.SINGLE_ICON) {
            if (req.singleTarget == null) {
                return "没有选择要导出的条目";
            }
            targets = List.of(req.singleTarget);
        } else {
            try {
                targets = RegistryScanner.scan(req);
            } catch (Throwable t) {
                ExporterLog.LOG.error("扫描注册表失败", t);
                return "扫描失败：" + t.getMessage();
            }
        }
        if (targets.isEmpty()) {
            return "所选命名空间没有可导出的内容";
        }
        request = req;
        running = true;
        index = 0;
        failures.clear();
        noVisualTargets.clear();
        suspectTargets.clear();
        error = "";
        lastResult = "";
        startedAt = System.currentTimeMillis();
        output = ExporterConfig.get().outputPath();
        log.clear();
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            running = false;
            return "无法创建输出目录：" + e.getMessage();
        }
        log.add("开始导出 " + targets.size() + " 条 → " + output);
        if (req.mode == ExportMode.JSON_ONLY) {
            try {
                LmsWriter.write(output, targets, false, ExporterConfig.get().effectNeutral);
                PreviewWriter.write(output, targets);
                finish("仅 JSON 导出完成");
            } catch (IOException e) {
                fail("写 JSON 失败：" + e.getMessage());
            }
        }
        return null;
    }

    public void tick() {
    }

    public void onHudRender(PoseStack pose) {
        if (!running || request == null || targetDone()) {
            return;
        }
        Target target = targets.get(index);
        currentLabel = displayName(target);
        boolean any = false;
        int baseSize = request.sizes.stream().mapToInt(Integer::intValue).max().orElse(128);
        BufferedImage base = null;
        if (target.kind != Target.Kind.EFFECT) {
            // 只在最大尺寸上离屏渲染一次，其余尺寸由它缩放而来：同一目标在多个尺寸之间
            // 连续离屏渲染时，上一尺寸的几何体会串进下一张纹理（表现为图标角落的缩小副本）。
            base = target.icons.get(baseSize);
            if (base == null) {
                base = target.kind == Target.Kind.ITEM
                        ? ItemIconRenderer.render(target.stack, baseSize)
                        : EntityIconRenderer.render(target.entityType, baseSize, 150.0F);
                if (base != null && !ImageUtil.blank(base)) {
                    target.icons.put(baseSize, base);
                    writePng(target, baseSize, base);
                    any = true;
                } else {
                    base = null;
                }
            }
        }
        for (int size : request.sizes) {
            if (target.icons.containsKey(size)) {
                continue;
            }
            BufferedImage image;
            if (base != null) {
                image = ImageUtil.fit(base, size);
            } else if (target.kind == Target.Kind.EFFECT) {
                image = EffectIconRenderer.render(target.id, size);
            } else {
                continue;
            }
            if (image != null && !ImageUtil.blank(image)) {
                target.icons.put(size, image);
                writePng(target, size, image);
                any = true;
            }
        }
        if (target.kind == Target.Kind.EFFECT) {
            int expected = EffectIconRenderer.sourceColorCount(target.id);
            target.expectedColorCount = expected;
            int actual = target.icons.containsKey(128) ? ImageUtil.colorCount(target.icons.get(128)) : 0;
            if (expected > 0 && actual != expected) {
                target.suspect = true;
                target.suspectReason = "sprite-upscale-blur";
            }
            if (target.suspect) {
                suspectTargets.put(target.id.toString(), target.suspectReason);
            }
        }
        if (target.kind == Target.Kind.ENTITY) {
            if (!target.icons.containsKey(128)) {
                BufferedImage retry = EntityIconRenderer.render(target.entityType, 128, 315.0F);
                if (retry != null && !ImageUtil.blank(retry)) {
                    target.icons.put(128, retry);
                    writePng(target, 128, retry);
                    any = true;
                }
            }
            if (!target.icons.containsKey(128)) {
                target.noVisual = true;
                noVisualTargets.put(target.id.toString(), "离屏渲染为空");
            } else {
                int colors = ImageUtil.colorCount(target.icons.get(128));
                if (colors <= 2) {
                    target.suspect = true;
                    target.suspectReason = "flat-color";
                    suspectTargets.put(target.id.toString(), target.suspectReason);
                }
            }
        }
        if (!any && target.kind == Target.Kind.ITEM) {
            failures.put(target.id.toString(), "渲染为空");
            log.add("渲染为空：" + target.id);
            ExporterLog.LOG.warn("渲染为空：{}", target.id);
        }
        index++;
        if (targetDone()) {
            String message;
            if (request.mode == ExportMode.SINGLE_ICON) {
                message = "单个图标导出完成（仅写入 PNG）";
            } else if (request.mode == ExportMode.ICONS_ONLY) {
                message = "图标导出完成";
            } else {
                message = "导出完成";
            }
            finish(message);
        }
    }

    private boolean targetDone() {
        return running && index >= targets.size();
    }

    private boolean writePng(Target target, int size, BufferedImage image) {
        try {
            Path dir = output.resolve(target.namespace()).resolve("icons");
            Files.createDirectories(dir);
            Path file = dir.resolve(target.path() + "_" + size + ".png");
            Files.write(file, ImageUtil.png(image));
            return true;
        } catch (IOException e) {
            failures.putIfAbsent(target.id.toString(), "写图失败：" + e.getMessage());
            log.add("写图失败：" + target.id + "：" + e.getMessage());
            return false;
        }
    }

    private void finish(String message) {
        try {
            if (request.mode == ExportMode.FULL) {
                LmsWriter.write(output, targets, true, ExporterConfig.get().effectNeutral);
                PreviewWriter.write(output, targets);
            }
            writeFailureReport();
            writeStatus("DONE", message);
            int success = Math.max(0, targets.size() - failures.size() - noVisualTargets.size());
            lastResult = message + " · 成功 " + success + "，失败 " + failures.size()
                    + (noVisualTargets.isEmpty() ? "" : "，无可见模型 " + noVisualTargets.size())
                    + (suspectTargets.isEmpty() ? "" : "，可疑 " + suspectTargets.size());
            if (!failures.isEmpty()) {
                lastResult += "（" + sampleIds(failures)
                        + (failures.size() > 3 ? "，见 failures.txt" : "") + "）";
            }
            log.add(lastResult);
        } catch (IOException e) {
            fail("写 JSON 失败：" + e.getMessage());
            return;
        }
        running = false;
        request = null;
    }

    private void fail(String message) {
        error = message;
        lastResult = "失败：" + message;
        try {
            writeStatus("FAILED", message);
        } catch (IOException ignored) {
        }
        log.add("失败：" + message);
        running = false;
        request = null;
    }

    private void writeStatus(String state, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        o.addProperty("message", message);
        o.addProperty("total", targets.size());
        o.addProperty("failed", failures.size());
        o.addProperty("noVisual", noVisualTargets.size());
        o.addProperty("suspect", suspectTargets.size());
        o.add("failedEntries", entriesArray(failures));
        o.add("noVisualEntries", entriesArray(noVisualTargets));
        o.add("suspectEntries", entriesArray(suspectTargets));
        o.addProperty("failureReport", "failures.txt");
        o.addProperty("output", output.toString());
        o.addProperty("startedAt", Instant.ofEpochMilli(startedAt).toString());
        o.addProperty("updatedAt", Instant.now().toString());
        Files.writeString(output.resolve("status.json"), GSON.toJson(o), StandardCharsets.UTF_8);
    }

    /** 每个条目一行：注册名 \t 原因；分失败 / 无可见模型 / 可疑三段。 */
    private void writeFailureReport() throws IOException {
        if (failures.isEmpty() && noVisualTargets.isEmpty() && suspectTargets.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "失败", failures);
        appendSection(sb, "无可见模型", noVisualTargets);
        appendSection(sb, "可疑", suspectTargets);
        Files.writeString(output.resolve("failures.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendSection(StringBuilder sb, String title, Map<String, String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append("# ").append(title).append('\n');
        entries.forEach((id, reason) -> sb.append(id).append('\t').append(reason).append('\n'));
        sb.append('\n');
    }

    private static JsonArray entriesArray(Map<String, String> entries) {
        JsonArray array = new JsonArray();
        entries.forEach((id, reason) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("reason", reason);
            array.add(entry);
        });
        return array;
    }

    private static String sampleIds(Map<String, String> entries) {
        List<String> ids = new ArrayList<>(entries.keySet());
        int shown = Math.min(3, ids.size());
        String sample = String.join("、", ids.subList(0, shown));
        return ids.size() > shown ? sample + " 等" : sample;
    }

    private static String displayName(Target target) {
        String name = !target.nameZh.isEmpty() ? target.nameZh : target.nameEn;
        return name.isEmpty() ? target.id.toString() : name;
    }

    public boolean isRunning() {
        return running;
    }

    public String error() {
        return error;
    }

    public String currentLabel() {
        return currentLabel;
    }

    /** 最近一次导出的结果摘要（成功/失败条数），供界面在空闲时展示。 */
    public String lastResult() {
        return lastResult;
    }

    public int done() {
        return index;
    }

    public int total() {
        return targets.size();
    }

    public int failed() {
        return failures.size();
    }

    public int noVisual() {
        return noVisualTargets.size();
    }

    public int suspect() {
        return suspectTargets.size();
    }

    public Path output() {
        return output;
    }

    public List<String> recentLog() {
        return new ArrayList<>(log);
    }
}
