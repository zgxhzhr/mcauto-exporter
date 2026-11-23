package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 开发/回归用自动导出。存在 config/meowarchive-auto.json 时，进入世界后自动跑一次。
 * 这不是 agent 协议，只是用来在没有输入设备的情况下验证导出链路。
 */
public final class AutoExport {
    private static final Gson GSON = new Gson();
    private static boolean loaded;
    private static String namespace = "";
    private static boolean exitWhenDone;
    private static String world = "";
    private static boolean worldRequested;
    private static int menuTicks;
    private static boolean started;
    private static boolean finished;
    private static long finishedAt;
    private static int waitTicks;
    /** 无人值守流程的硬上限：超时后无论导出是否完成都收尾，避免客户端永远卡住。 */
    private static long deadline;
    private static int timeoutSeconds = 600;

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
        if (deadline == 0L) {
            deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        }
        if (!finished && System.currentTimeMillis() > deadline) {
            ExporterLog.LOG.warn("自动导出超时（{} 秒），强制收尾：started={}, running={}",
                    timeoutSeconds, started, ExportService.get().isRunning());
            writeTimeoutStatus();
            finish();
            return;
        }
        if (!started) {
            if (mc.level == null) {
                launchWorldIfConfigured(mc);
                return;
            }
            if (mc.player == null) {
                return;
            }
            if (mc.screen != null) {
                // 失焦自动暂停会弹开暂停菜单并把集成服务器挂起，这里主动关掉，保证导出能继续
                if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
                    mc.setScreen(null);
                }
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
                ExporterLog.LOG.error("自动导出启动失败: {}", error);
                finish();
            }
            return;
        }
        if (!finished && !ExportService.get().isRunning()) {
            finish();
        }
        if (finished && exitWhenDone && System.currentTimeMillis() - finishedAt > 3000L) {
            ExporterLog.LOG.info("自动导出结束，关闭客户端");
            mc.stop();
        }
    }

    private static void finish() {
        finished = true;
        finishedAt = System.currentTimeMillis();
    }

    /**
     * 配置了 world 且停在主菜单时自动建/进世界：存档存在就读取，不存在就用创造模式新建。
     * 全程无输入，供无人值守导出使用。
     */
    private static void launchWorldIfConfigured(Minecraft mc) {
        if (world.isBlank() || worldRequested) {
            return;
        }
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
            return;
        }
        if (++menuTicks < 40) {
            return;
        }
        worldRequested = true;
        try {
            if (mc.getLevelSource().levelExists(world)) {
                ExporterLog.LOG.info("自动载入世界：{}", world);
                mc.loadLevel(world);
            } else {
                ExporterLog.LOG.info("自动创建世界：{}", world);
                net.minecraft.core.RegistryAccess.Frozen access =
                        net.minecraft.core.RegistryAccess.BUILTIN.get();
                net.minecraft.world.level.LevelSettings settings =
                        new net.minecraft.world.level.LevelSettings(
                                world,
                                net.minecraft.world.level.GameType.CREATIVE,
                                false,
                                net.minecraft.world.Difficulty.PEACEFUL,
                                true,
                                new net.minecraft.world.level.GameRules(),
                                new net.minecraft.world.level.DataPackConfig(
                                        java.util.Collections.singletonList("vanilla"),
                                        java.util.Collections.emptyList()));
                mc.createLevel(world, settings, access,
                        net.minecraft.world.level.levelgen.WorldGenSettings.makeDefault(access));
            }
        } catch (Throwable t) {
            ExporterLog.LOG.error("自动进入世界失败: {}", t.toString());
        }
    }

    /** 超时时补一份 FAILED 状态，外部自动化流程读取 status.json 即可判定失败，而不是无期限等待。 */
    private static void writeTimeoutStatus() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("state", "FAILED");
            o.addProperty("message", "自动导出超时（" + timeoutSeconds + " 秒）"
                    + (started ? "，导出未能在时限内完成" : "，未能进入世界或启动导出"));
            o.addProperty("timeoutSeconds", timeoutSeconds);
            o.addProperty("started", started);
            Path dir = io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig.get().outputPath();
            Files.createDirectories(dir);
            Files.write(dir.resolve("status.json"), GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ExporterLog.LOG.warn("写入超时状态失败: {}", e.toString());
        }
    }

    private static void load() {
        loaded = true;
        Path path = Services.PLATFORM.gameDir().resolve("config").resolve("meowarchive-auto.json");
        if (Files.isRegularFile(path)) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (json != null) {
                    namespace = json.has("namespace") ? json.get("namespace").getAsString() : "";
                    world = json.has("world") ? json.get("world").getAsString() : "";
                    exitWhenDone = json.has("exitWhenDone") && json.get("exitWhenDone").getAsBoolean();
                    if (json.has("timeoutSeconds")) {
                        timeoutSeconds = Math.max(10, json.get("timeoutSeconds").getAsInt());
                    }
                }
            } catch (Exception e) {
                ExporterLog.LOG.warn("读取 meowarchive-auto.json 失败: {}", e.toString());
            }
        }
        String prop = System.getProperty("meowarchive.autoNamespace", "");
        if (!prop.isBlank()) {
            namespace = prop;
            exitWhenDone = Boolean.getBoolean("meowarchive.autoExit");
        }
        String timeoutProp = System.getProperty("meowarchive.autoTimeoutSeconds", "");
        if (!timeoutProp.isBlank()) {
            try {
                timeoutSeconds = Math.max(10, Integer.parseInt(timeoutProp.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!namespace.isBlank()) {
            ExporterLog.LOG.info("自动导出已配置：namespace={}, exit={}, timeout={}s",
                    namespace, exitWhenDone, timeoutSeconds);
        }
    }
}
