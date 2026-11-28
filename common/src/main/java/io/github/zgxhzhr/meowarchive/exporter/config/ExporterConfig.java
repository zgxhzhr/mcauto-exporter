package io.github.zgxhzhr.meowarchive.exporter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ExporterConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ExporterConfig instance;

    public String outputDir = "meowarchive-export";
    public List<String> targets = new ArrayList<>(List.of("items", "blocks", "entities", "effects"));
    public List<Integer> sizes = new ArrayList<>(List.of(32, 128));
    public String effectNeutral = "BUFF";
    public boolean embedIcons = true;
    /** 是否额外导出动画 GIF（物品）。默认开启。 */
    public boolean animatedIcons = true;

    public static ExporterConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        Path path = configPath();
        ExporterConfig cfg = new ExporterConfig();
        if (Files.isRegularFile(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                ExporterConfig read = GSON.fromJson(text, ExporterConfig.class);
                if (read != null) {
                    cfg = read;
                }
            } catch (Exception e) {
                ExporterLog.LOG.warn("读取配置失败，使用默认值: {}", e.toString());
            }
        }
        if (cfg.targets == null || cfg.targets.isEmpty()) {
            cfg.targets = new ArrayList<>(List.of("items", "blocks", "entities", "effects"));
        }
        if (cfg.sizes == null || cfg.sizes.isEmpty()) {
            cfg.sizes = new ArrayList<>(List.of(32, 128));
        }
        instance = cfg;
        save();
    }

    public static void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ExporterLog.LOG.warn("保存配置失败: {}", e.toString());
        }
    }

    public Path outputPath() {
        Path p = Path.of(outputDir);
        if (p.isAbsolute()) {
            return p;
        }
        return Services.PLATFORM.gameDir().resolve(outputDir);
    }

    private static Path configPath() {
        return Services.PLATFORM.configDir().resolve("meowarchive.json");
    }
}
