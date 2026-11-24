package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.client.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 自动导出：仓库面向 AI 的全自动流水线入口。存在 config/meowarchive-auto.json 时，
 * 客户端按配置自动创建/载入世界，进世界后自动跑一次导出，完成后按配置退出。
 * world 字段让无人值守流程跳过主菜单手动点击；留空则由外部流程负责把客户端送进世界。
 */
public final class AutoExport {
    private static final Gson GSON = new Gson();
    private static boolean loaded;
    private static String namespace = "";
    private static String world = "";
    private static boolean exitWhenDone;
    private static boolean started;
    private static boolean finished;
    private static boolean worldRequested;
    private static int menuTicks;
    private static long finishedAt;
    private static int waitTicks;
    /** 无人值守流程的硬上限：超过后无论导出是否完成都收尾，避免客户端永远卡住。 */
    private static long deadline;
    private static int timeoutSeconds = 600;

    private AutoExport() {
    }

    public static void tick() {
        if (!loaded) {
            load();
        }
        if (namespace.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
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
            if (mc.theWorld == null) {
                launchWorldIfConfigured(mc);
                return;
            }
            if (mc.thePlayer == null) {
                return;
            }
            if (mc.currentScreen != null) {
                // 失焦自动暂停会弹开游戏菜单并把集成服务器暂停，这里主动关掉，保证导出能继续
                if (mc.currentScreen instanceof GuiIngameMenu) {
                    mc.displayGuiScreen(null);
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
            mc.shutdown();
        }
    }

    private static void finish() {
        finished = true;
        finishedAt = System.currentTimeMillis();
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

    /** 配置了 world 且停在主菜单时，自动创建（不存在则新建）并进入该存档，全程创造模式避免被打断。 */
    private static void launchWorldIfConfigured(Minecraft mc) {
        if (world.isEmpty() || worldRequested) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiMainMenu)) {
            return;
        }
        if (++menuTicks < 40) {
            return;
        }
        worldRequested = true;
        ExporterLog.LOG.info("自动创建/载入世界：{}", world);
        WorldSettings settings = new WorldSettings(0L, WorldSettings.GameType.CREATIVE,
                false, false, WorldType.DEFAULT);
        mc.launchIntegratedServer(world, world, settings);
    }

    private static void load() {
        loaded = true;
        Path path = ClientPlatform.configDir().resolve("meowarchive-auto.json");
        if (Files.isRegularFile(path)) {
            try {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                JsonObject json = GSON.fromJson(text, JsonObject.class);
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
        if (!prop.isEmpty()) {
            namespace = prop;
            exitWhenDone = Boolean.getBoolean("meowarchive.autoExit");
        }
        String timeoutProp = System.getProperty("meowarchive.autoTimeoutSeconds", "");
        if (!timeoutProp.isEmpty()) {
            try {
                timeoutSeconds = Math.max(10, Integer.parseInt(timeoutProp.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!namespace.isEmpty()) {
            ExporterLog.LOG.info("自动导出已配置：namespace={}, exit={}, timeout={}s",
                    namespace, exitWhenDone, timeoutSeconds);
        }
    }
}
