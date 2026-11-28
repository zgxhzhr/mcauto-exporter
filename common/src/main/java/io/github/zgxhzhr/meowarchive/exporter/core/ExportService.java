package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.render.AnimatedIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.EffectIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import io.github.zgxhzhr.meowarchive.exporter.render.ItemIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.scan.RegistryScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

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
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.Map;

/**
 * 导出编排：扫描 → 每帧渲染一个目标 → 落 PNG → 写 LMS JSON。
 * 所有方法都在客户端主线程调用；渲染只在 HUD 阶段执行。
 */
public final class ExportService {
    private static final ExportService INSTANCE = new ExportService();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean running;
    private boolean capturing;
    private long captureGen;
    private int captureFrames;
    private int captureSize;
    private final Set<String> retried = new HashSet<>();
    /** 已确认渲染为空的「条目#尺寸」，避免同一尺寸无限重试。 */
    private final Set<String> skippedSizes = new HashSet<>();
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
        retried.clear();
        skippedSizes.clear();
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

    public void onHudRender(GuiGraphics graphics) {
        if (!running || request == null || targetDone()) {
            return;
        }
        if (capturing) {
            // 回读回调依赖后续帧提交命令；窗口最小化等情况下可能永远等不到，超时后放弃该尺寸
            if (++captureFrames > 600) {
                ExporterLog.LOG.warn("捕获超时，放弃 {} size={}", currentLabel, captureSize);
                captureGen++;
                capturing = false;
                advance(targets.get(index));
            }
            return;
        }
        Target target = targets.get(index);
        currentLabel = displayName(target);
        int pendingSize = -1;
        for (int size : request.sizes) {
            if (!target.icons.containsKey(size) && !skippedSizes.contains(target.id + "#" + size)) {
                pendingSize = size;
                break;
            }
        }
        if (pendingSize < 0) {
            advance(target);
            return;
        }
        final int size = pendingSize;
        final long gen = ++captureGen;
        captureSize = size;
        captureFrames = 0;
        capturing = true;
        Consumer<BufferedImage> done = image -> {
            if (gen != captureGen) {
                return;
            }
            capturing = false;
            if (image != null && !ImageUtil.blank(image)) {
                target.icons.put(size, image);
                writePng(target, size, image);
            } else {
                skippedSizes.add(target.id + "#" + size);
            }
        };
        switch (target.kind) {
            case ITEM -> ItemIconRenderer.renderAsync(target.stack, size, done);
            case ENTITY -> EntityIconRenderer.renderAsync(target.entityType, size, 30.0F, done);
            case EFFECT -> done.accept(EffectIconRenderer.render(target.id, size));
        }
    }

    /** 一个条目的所有尺寸都处理完：做校验、必要时重试，然后进入下一个条目。 */
    private void advance(Target target) {
        if (target.kind == Target.Kind.ENTITY && !target.icons.containsKey(128)
                && retried.add(target.id.toString())) {
            capturing = true;
            EntityIconRenderer.renderAsync(target.entityType, 128, 30.0F, retry -> {
                capturing = false;
                if (retry != null && !ImageUtil.blank(retry)) {
                    target.icons.put(128, retry);
                    writePng(target, 128, retry);
                }
                finalizeTarget(target);
            });
            return;
        }
        finalizeTarget(target);
    }

    private void finalizeTarget(Target target) {
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
        if (target.kind == Target.Kind.ITEM) {
            // 平面动画物品额外导出 GIF，静态 PNG 仍保留作兜底
            for (int size : request.sizes) {
                exportAnimated(target, size);
            }
        }
        if (target.icons.isEmpty() && target.kind == Target.Kind.ITEM) {
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

    /** 平面动画物品：合成 GIF 落盘并缓存字节，供 JSON / 预览页直接内嵌。 */
    private void exportAnimated(Target target, int size) {
        try {
            AnimatedIconRenderer.AnimatedIcon icon = AnimatedIconRenderer.render(target.stack, size);
            if (icon == null) {
                return;
            }
            byte[] gif = ImageUtil.gif(icon.frames, icon.delaysMillis);
            if (writeGif(target, size, gif)) {
                target.animatedGifs.put(size, gif);
                log.add("动图 " + target.id + " @" + size + "：" + icon.frames.size() + " 帧");
            }
        } catch (Throwable t) {
            ExporterLog.LOG.warn("GIF 导出失败: {}", target.id, t);
        }
    }

    private boolean writeGif(Target target, int size, byte[] gif) {
        try {
            Path dir = output.resolve(target.namespace()).resolve("icons");
            Files.createDirectories(dir);
            Files.write(dir.resolve(target.path() + "_" + size + ".gif"), gif);
            return true;
        } catch (IOException e) {
            failures.putIfAbsent(target.id.toString(), "写 GIF 失败：" + e.getMessage());
            log.add("写 GIF 失败：" + target.id + "：" + e.getMessage());
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
