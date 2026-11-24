package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;
import net.minecraft.client.Minecraft;
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
    private static String world = "";
    private static boolean worldRequested;
    private static int menuTicks;
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
        Minecraft mc = Minecraft.getInstance();
        launchWorldIfConfigured(mc);
        if (namespace.isBlank()) {
            return;
        }
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
     * 配置了 world 且还停在主菜单时，自动载入已有存档，省掉手动点击。
     * 1.19.2 的 WorldOpenFlows#loadLevel(Screen, String) 就是「单人游戏 → 双击存档」这条路径。
     */
    /**
     * 配置了 world 且停在主菜单时自动载入已有存档，参考 1.17.1 分支的做法：
     * 等 40 个菜单 tick 再动手，只在 TitleScreen 上触发，失败只记错误不抛。
     * 1.19.2 把「单人游戏 → 双击存档」收进了 WorldOpenFlows#loadLevel(Screen, String)。
     */
    private static void launchWorldIfConfigured(Minecraft mc) {
        if (world.isBlank() || worldRequested || mc.level != null) {
            return;
        }
        if (!(mc.screen instanceof TitleScreen)) {
            return;
        }
        if (++menuTicks < 40) {
            return;
        }
        worldRequested = true;
        try {
            boolean exists = mc.getLevelSource().levelExists(world);
            ExporterLog.LOG.info("自动{}世界：{}", exists ? "载入" : "创建", world);
            if (exists) {
                mc.createWorldOpenFlows().loadLevel(mc.screen, world);
            } else {
                ExporterLog.LOG.error("存档不存在，无法自动进入：{}", world);
            }
        } catch (Throwable t) {
            ExporterLog.LOG.error("自动进入世界失败: {}", t.toString());
        }
    }

    private static void finish() {
        finished = true;
        finishedAt = System.currentTimeMillis();
    }

    private static void load() {
        loaded = true;
        Path path = Services.PLATFORM.gameDir().resolve("config").resolve("meowarchive-auto.json");
        if (Files.isRegularFile(path)) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (json != null) {
                    namespace = json.has("namespace") ? json.get("namespace").getAsString() : "";
                    exitWhenDone = json.has("exitWhenDone") && json.get("exitWhenDone").getAsBoolean();
                    world = json.has("world") ? json.get("world").getAsString() : "";
                }
            } catch (Exception e) {
                ExporterLog.LOG.warn("读取 meowarchive-auto.json 失败: {}", e.toString());
            }
        }
        String propWorld = System.getProperty("meowarchive.autoWorld", "");
        if (!propWorld.isBlank()) {
            world = propWorld;
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
