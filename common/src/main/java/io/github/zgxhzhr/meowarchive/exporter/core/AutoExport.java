package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

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
    private static boolean worldAttempted;
    private static int worldWaitTicks;
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
        if (namespace.isBlank() && world.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            if (mc.level == null) {
                // quickPlay 在没有真实会话 / 从启动器直接点启动时不会生效，
                // 这里在标题界面兜底：存档存在就打开，不存在就用创造模式新建。
                openOrCreateWorld(mc);
                return;
            }
            // 失焦自动暂停会弹开暂停菜单并把集成服务器挂起，主动关掉，保证导出能继续
            if (mc.screen instanceof PauseScreen) {
                mc.setScreen(null);
                return;
            }
            if (mc.player == null || mc.screen != null) {
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
     * 停在标题界面且没有世界时自动进入：存档存在就直接读，不存在就用创造模式新建。
     * 供没有 quickPlay 参数（例如从启动器直接点启动）的无人值守导出使用。
     */
    private static void openOrCreateWorld(Minecraft mc) {
        if (world.isBlank() || worldAttempted) {
            return;
        }
        // 只在标题界面动手，避免打断加载 / 保存等过渡界面
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) {
            return;
        }
        if (++worldWaitTicks < 40) {
            return;
        }
        worldAttempted = true;
        try {
            if (mc.getLevelSource().levelExists(world)) {
                ExporterLog.LOG.info("自动打开世界：{}", world);
                mc.createWorldOpenFlows().openWorld(world, () -> mc.setScreen(new TitleScreen()));
                return;
            }
            LevelSettings settings = new LevelSettings(
                    world,
                    GameType.CREATIVE,
                    false,
                    Difficulty.NORMAL,
                    true,
                    new GameRules(FeatureFlags.VANILLA_SET),
                    WorldDataConfiguration.DEFAULT);
            ExporterLog.LOG.info("自动新建世界（创造模式）：{}", world);
            mc.createWorldOpenFlows().createFreshLevel(
                    world,
                    settings,
                    WorldOptions.defaultWithRandomSeed(),
                    WorldPresets::createNormalWorldDimensions,
                    new TitleScreen());
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
