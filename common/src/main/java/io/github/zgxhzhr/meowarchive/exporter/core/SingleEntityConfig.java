package io.github.zgxhzhr.meowarchive.exporter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityRenderOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 单实体预览界面自己的配置，与批量导出的 ExporterConfig 分开存放，互不影响。
 */
public final class SingleEntityConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SingleEntityConfig instance;

    public EntityRenderOptions options = new EntityRenderOptions();
    public List<Integer> sizes = new ArrayList<>(Arrays.asList(128));
    /** 渲染现场实体（保留装备/NBT 外观）还是按实体类型新建一个。 */
    public boolean useLiveEntity = true;

    public static SingleEntityConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        Path path = configPath();
        SingleEntityConfig cfg = new SingleEntityConfig();
        if (Files.isRegularFile(path)) {
            try {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                SingleEntityConfig read = GSON.fromJson(text, SingleEntityConfig.class);
                if (read != null) {
                    cfg = read;
                }
            } catch (Exception e) {
                ExporterLog.LOG.warn("读取单实体配置失败，使用默认值: {}", e.toString());
            }
        }
        if (cfg.options == null) {
            cfg.options = new EntityRenderOptions();
        }
        if (cfg.sizes == null || cfg.sizes.isEmpty()) {
            cfg.sizes = new ArrayList<>(Arrays.asList(128));
        }
        instance = cfg;
        save();
    }

    public static void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.write(path, GSON.toJson(get()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ExporterLog.LOG.warn("保存单实体配置失败: {}", e.toString());
        }
    }

    private static Path configPath() {
        return Services.PLATFORM.configDir().resolve("meowarchive-single.json");
    }
}
