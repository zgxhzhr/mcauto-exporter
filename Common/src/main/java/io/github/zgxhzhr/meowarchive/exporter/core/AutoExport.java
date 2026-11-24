package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;

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
    private static String world = "";
    private static boolean exitWhenDone;
    private static boolean started;
    private static boolean finished;
    private static boolean worldAttempted;
    private static boolean tickLogged;
    private static boolean worldDiagLogged;
    private static int worldDiagTicks;
    private static int worldWaitTicks;
    private static int waitLogTicks;
    private static long finishedAt;
    private static int waitTicks;

    private AutoExport() {
    }

    public static void tick() {
        if (!loaded) {
            load();
        }
        if (!tickLogged) {
            tickLogged = true;
            Minecraft first = Minecraft.getInstance();
            ExporterLog.LOG.info("AutoExport tick 首次调用：screen={}, level={}",
                    first.screen == null ? "null" : first.screen.getClass().getName(),
                    first.level != null);
        }
        if (namespace.isBlank() && world.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!worldDiagLogged && ++worldDiagTicks > 100) {
                worldDiagLogged = true;
                ExporterLog.LOG.info("AutoExport 等待中：screen={}, overlay={}",
                        mc.screen == null ? "null" : mc.screen.getClass().getName(),
                        mc.getOverlay() == null ? "null" : mc.getOverlay().getClass().getName());
            }
            openWorldIfIdle(mc);
            return;
        }
        if (!started) {
            // 无输入设备启动时窗口常失去焦点，单人游戏会每帧弹暂停界面，先关掉再继续判断
            if (mc.screen instanceof PauseScreen) {
                mc.setScreen(null);
            }
            if (mc.player == null || mc.screen != null) {
                if (++waitLogTicks % 100 == 0) {
                    ExporterLog.LOG.info("AutoExport 等待可导出状态：screen={}, player={}",
                            mc.screen == null ? "null" : mc.screen.getClass().getName(),
                            mc.player != null);
                }
                return;
            }
            if (++waitTicks < 40) {
                return;
            }
            started = true;
            ExporterLog.LOG.info("自动导出启动：namespace={}, targets={}", namespace, ExportService.get().total());
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

    /**
     * quickPlay 在没有真实会话 / 某些加载器下会被跳过，这里做兜底：
     * 停在标题界面或“世界不存在”界面时，直接用 WorldOpenFlows 打开指定存档。
     */
    private static void openWorldIfIdle(Minecraft mc) {
        if (world.isBlank() || worldAttempted) {
            return;
        }
        if (mc.screen == null || mc.getOverlay() != null) {
            return;
        }
        if (mc.screen instanceof GenericDirtMessageScreen) {
            return;
        }
        if (++worldWaitTicks < 20) {
            return;
        }
        worldAttempted = true;
        if (!mc.getLevelSource().levelExists(world)) {
            ExporterLog.LOG.warn("自动世界不存在: {}", world);
            return;
        }
        ExporterLog.LOG.info("自动打开世界 {}（当前界面 {}）", world, mc.screen.getClass().getSimpleName());
        mc.createWorldOpenFlows().loadLevel(new TitleScreen(), world);
    }

    private static void finish() {
        finished = true;
        finishedAt = System.currentTimeMillis();
    }

    private static void load() {
        loaded = true;
        Path path = Services.PLATFORM.gameDir().resolve("config").resolve("meowarchive-auto.json");
        ExporterLog.LOG.info("AutoExport 读取配置 {}（存在={}）", path, Files.isRegularFile(path));
        if (Files.isRegularFile(path)) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (json != null) {
                    namespace = json.has("namespace") ? json.get("namespace").getAsString() : "";
                    world = json.has("world") ? json.get("world").getAsString() : "";
                    exitWhenDone = json.has("exitWhenDone") && json.get("exitWhenDone").getAsBoolean();
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
        if (!namespace.isBlank()) {
            ExporterLog.LOG.info("自动导出已配置：namespace={}, exit={}", namespace, exitWhenDone);
        }
    }
}
