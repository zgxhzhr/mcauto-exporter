package io.github.zgxhzhr.meowarchive.exporter.core;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityRenderOptions;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 单实体导出：按当前配置的多档尺寸各渲染一张 PNG，写到导出目录的 single 子目录。
 */
public final class SingleEntityExporter {
    private SingleEntityExporter() {
    }

    /** 返回给界面展示的结果文本。 */
    public static String export(Entity entity, EntityRenderOptions options, List<Integer> sizes) {
        if (entity == null) {
            return "没有可导出的实体";
        }
        if (sizes == null || sizes.isEmpty()) {
            return "至少选择一个尺寸";
        }
        Path dir = ExporterConfig.get().outputPath().resolve("single");
        String base = fileName(entity);
        int ok = 0;
        String lastError = "";
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            return "无法创建输出目录：" + e.getMessage();
        }
        for (int size : sizes) {
            try {
                EntityRenderOptions one = options.copy();
                BufferedImage image = EntityIconRenderer.render(entity, size, one);
                if (image == null) {
                    lastError = size + "px 渲染为空";
                    continue;
                }
                ExporterLog.LOG.info("单实体导出 {} {}px yaw={} head={} zoom={} offX={} offY={} margin={}",
                        base, size, one.yaw, one.headYaw, one.zoom, one.offsetX, one.offsetY,
                        one.margin);
                Path file = dir.resolve(base + "_" + size + ".png");
                byte[] png = ImageUtil.png(image);
                try {
                    Files.write(file, png);
                } catch (Exception busy) {
                    // 目标文件被看图软件/编辑器占用时另存一份，避免整个导出失败
                    Path alt = dir.resolve(base + "_" + size + "_" + (System.currentTimeMillis() % 100000)
                            + ".png");
                    Files.write(alt, png);
                    file = alt;
                    lastError = size + "px 原文件被占用，已另存 " + alt.getFileName();
                }
                ok++;
            } catch (Throwable e) {
                lastError = size + "px：" + e.getMessage();
            }
        }
        if (ok == 0) {
            ExporterLog.LOG.warn("单实体导出失败：{}", lastError);
            return "导出失败：" + lastError;
        }
        String text = "已导出 " + ok + " 张 → " + dir;
        if (lastError.isEmpty()) {
            text += "\\" + base + "_*.png";
        }
        if (!lastError.isEmpty()) {
            text += "（部分失败：" + lastError + "）";
        }
        ExporterLog.LOG.info(text);
        return text;
    }

    /** 用实体注册名生成安全的文件名前缀。 */
    public static String fileName(Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return id.replace(':', '_').replace('/', '_');
    }

    /**
     * 复制一份实体用于预览/导出。
     *
     * <p>不能直接渲染世界里的实体：像史莱姆的挤压动画、生物的手臂摆动都依赖运行时字段，
     * 不同时刻渲染出来大小/姿态会变，导致预览和导出对不上。复制一份新实体并拷贝 NBT，
     * 既保留装备/变体外观，又让运行时动画字段归零，同一套参数永远得到同一张图。
     */
    public static Entity copyOf(Entity source) {
        if (source == null) {
            return null;
        }
        Entity copy = null;
        try {
            copy = source.getType().create(source.level);
        } catch (Throwable ignored) {
            copy = null;
        }
        if (copy == null) {
            return null;
        }
        try {
            CompoundTag tag = new CompoundTag();
            source.saveWithoutId(tag);
            copy.load(tag);
        } catch (Throwable ignored) {
            // NBT 复制失败时退化为裸实体，至少保证能渲染
        }
        copy.setPos(source.getX(), source.getY(), source.getZ());
        copy.setYRot(source.getYRot());
        copy.setXRot(source.getXRot());
        return copy;
    }
}
