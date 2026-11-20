package io.github.zgxhzhr.mcauto.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.mcauto.exporter.ExporterLog;
import io.github.zgxhzhr.mcauto.exporter.config.ExporterConfig;
import io.github.zgxhzhr.mcauto.exporter.render.EffectIconRenderer;
import io.github.zgxhzhr.mcauto.exporter.render.EntityIconRenderer;
import io.github.zgxhzhr.mcauto.exporter.render.ImageUtil;
import io.github.zgxhzhr.mcauto.exporter.render.ItemIconRenderer;
import io.github.zgxhzhr.mcauto.exporter.scan.RegistryScanner;
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
import java.util.List;

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
    private int failed;
    private int noVisual;
    private int suspect;
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
        failed = 0;
        noVisual = 0;
        suspect = 0;
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
        Target target = targets.get(index);
        currentLabel = displayName(target);
        boolean any = false;
        for (int size : request.sizes) {
            if (target.icons.containsKey(size)) {
                continue;
            }
            BufferedImage image = switch (target.kind) {
                case ITEM -> ItemIconRenderer.render(target.stack, size);
                case ENTITY -> EntityIconRenderer.render(target.entityType, size, 150.0F);
                case EFFECT -> EffectIconRenderer.render(target.id, size);
            };
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
            suspect += target.suspect ? 1 : 0;
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
                noVisual++;
            } else {
                int colors = ImageUtil.colorCount(target.icons.get(128));
                if (colors <= 2) {
                    target.suspect = true;
                    target.suspectReason = "flat-color";
                    suspect++;
                }
            }
        }
        if (!any && target.kind == Target.Kind.ITEM) {
            failed++;
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

    private void writePng(Target target, int size, BufferedImage image) {
        try {
            Path dir = output.resolve("_data").resolve(target.namespace());
            Files.createDirectories(dir);
            Path file = dir.resolve(target.path() + "_" + size + ".png");
            Files.write(file, ImageUtil.png(image));
        } catch (IOException e) {
            log.add("写图失败：" + target.id + "：" + e.getMessage());
        }
    }

    private void finish(String message) {
        try {
            if (request.mode == ExportMode.FULL) {
                LmsWriter.write(output, targets, true, ExporterConfig.get().effectNeutral);
            }
            writeStatus("DONE", message);
            int success = Math.max(0, targets.size() - failed - noVisual);
            lastResult = message + " · 成功 " + success + "，失败 " + failed
                    + (noVisual > 0 ? "，无可见模型 " + noVisual : "")
                    + (suspect > 0 ? "，可疑 " + suspect : "");
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
        o.addProperty("failed", failed);
        o.addProperty("noVisual", noVisual);
        o.addProperty("suspect", suspect);
        o.addProperty("output", output.toString());
        o.addProperty("startedAt", Instant.ofEpochMilli(startedAt).toString());
        o.addProperty("updatedAt", Instant.now().toString());
        Files.writeString(output.resolve("status.json"), GSON.toJson(o), StandardCharsets.UTF_8);
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
        return failed;
    }

    public int noVisual() {
        return noVisual;
    }

    public int suspect() {
        return suspect;
    }

    public Path output() {
        return output;
    }

    public List<String> recentLog() {
        return new ArrayList<>(log);
    }
}
