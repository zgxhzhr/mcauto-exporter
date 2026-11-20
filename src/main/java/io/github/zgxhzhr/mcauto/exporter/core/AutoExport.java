package io.github.zgxhzhr.mcauto.exporter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.mcauto.exporter.McAutoExporter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 开发/回归用自动导出。存在 config/mcauto-auto.json 时，进入世界后自动跑一次。
 * 这不是 agent 协议，只是用来在没有输入设备的情况下验证导出链路。
 */
public final class AutoExport {
    private static final Gson GSON = new Gson();
    private static boolean loaded;
    private static String namespace = "";
    private static boolean exitWhenDone;
    private static boolean started;
    private static boolean finished;
    private static long finishedAt;
    private static int waitTicks;

    private AutoExport() {
    }

    public static void tick() {
        if (!loaded) {
            load();
        }
        if (namespace.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            if (mc.level == null || mc.player == null || mc.screen != null) {
                return;
            }
            if (++waitTicks < 40) {
                return;
            }
            started = true;
            ExportRequest request = new ExportRequest(ExportMode.FULL);
            request.namespaces.add(namespace);
            request.kinds.add(Target.Kind.ITEM);
            request.kinds.add(Target.Kind.ENTITY);
            request.kinds.add(Target.Kind.EFFECT);
            request.sizes.add(32);
            request.sizes.add(128);
            String error = ExportService.get().start(request);
            if (error != null) {
                McAutoExporter.LOG.error("自动导出启动失败: {}", error);
                finish();
            }
            return;
        }
        if (!finished && !ExportService.get().isRunning()) {
            finish();
        }
        if (finished && exitWhenDone && System.currentTimeMillis() - finishedAt > 3000L) {
            McAutoExporter.LOG.info("自动导出结束，关闭客户端");
            mc.stop();
        }
    }

    private static void finish() {
        finished = true;
        finishedAt = System.currentTimeMillis();
    }

    private static void load() {
        loaded = true;
        Path path = FabricLoader.getInstance().getGameDir().resolve("config").resolve("mcauto-auto.json");
        if (Files.isRegularFile(path)) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (json != null) {
                    namespace = json.has("namespace") ? json.get("namespace").getAsString() : "";
                    exitWhenDone = json.has("exitWhenDone") && json.get("exitWhenDone").getAsBoolean();
                }
            } catch (Exception e) {
                McAutoExporter.LOG.warn("读取 mcauto-auto.json 失败: {}", e.toString());
            }
        }
        String prop = System.getProperty("mcauto.autoNamespace", "");
        if (!prop.isBlank()) {
            namespace = prop;
            exitWhenDone = Boolean.getBoolean("mcauto.autoExit");
        }
        if (!namespace.isBlank()) {
            McAutoExporter.LOG.info("自动导出已配置：namespace={}, exit={}", namespace, exitWhenDone);
        }
    }
}
