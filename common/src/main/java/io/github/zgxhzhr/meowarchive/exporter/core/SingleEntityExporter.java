package io.github.zgxhzhr.meowarchive.exporter.core;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityRenderOptions;
import io.github.zgxhzhr.meowarchive.exporter.render.ImageUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单实体导出：按当前配置的多档尺寸各渲染一张 PNG，写到导出目录的 single 子目录。
 * 26.x 的离屏渲染是异步的，所以导出也走回调。
 */
public final class SingleEntityExporter {
    private SingleEntityExporter() {
    }

    /** 逐尺寸渲染并写盘，全部结束后回调结果文本。 */
    public static void exportAsync(Entity entity, EntityRenderOptions options, List<Integer> sizes,
                                   Consumer<String> done) {
        if (entity == null) {
            done.accept("没有可导出的实体");
            return;
        }
        if (sizes == null || sizes.isEmpty()) {
            done.accept("至少选择一个尺寸");
            return;
        }
        Path dir = ExporterConfig.get().outputPath().resolve("single");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            done.accept("无法创建输出目录：" + e.getMessage());
            return;
        }
        List<Integer> list = new ArrayList<>(sizes);
        exportOne(entity, options, list, 0, dir, fileName(entity), 0, "", done);
    }

    private static void exportOne(Entity entity, EntityRenderOptions options, List<Integer> sizes, int index,
                                  Path dir, String base, int ok, String lastError, Consumer<String> done) {
        if (index >= sizes.size()) {
            String text;
            if (ok == 0) {
                ExporterLog.LOG.warn("单实体导出失败：{}", lastError);
                done.accept("导出失败：" + lastError);
                return;
            }
            text = "已导出 " + ok + " 张 → " + dir;
            if (lastError.isEmpty()) {
                text += "\\" + base + "_*.png";
            } else {
                text += "（部分失败：" + lastError + "）";
            }
            ExporterLog.LOG.info(text);
            done.accept(text);
            return;
        }
        int size = sizes.get(index);
        EntityRenderOptions one = options.copy();
        EntityIconRenderer.renderAsync(entity, size, one, image -> {
            int nextOk = ok;
            String nextError = lastError;
            if (image == null) {
                nextError = size + "px 渲染为空";
            } else {
                try {
                    ExporterLog.LOG.info("单实体导出 {} {}px yaw={} head={} zoom={} offX={} offY={} margin={}",
                            base, size, one.yaw, one.headYaw, one.zoom, one.offsetX, one.offsetY, one.margin);
                    Path file = dir.resolve(base + "_" + size + ".png");
                    byte[] png = ImageUtil.png(image);
                    try {
                        Files.write(file, png);
                    } catch (Exception busy) {
                        // 目标文件被看图软件/编辑器占用时另存一份，避免整个导出失败
                        Path alt = dir.resolve(base + "_" + size + "_"
                                + (System.currentTimeMillis() % 100000) + ".png");
                        Files.write(alt, png);
                        nextError = size + "px 原文件被占用，已另存 " + alt.getFileName();
                    }
                    nextOk++;
                } catch (Throwable e) {
                    nextError = size + "px：" + e.getMessage();
                }
            }
            exportOne(entity, options, sizes, index + 1, dir, base, nextOk, nextError, done);
        });
    }

    /** 用实体注册名生成安全的文件名前缀。 */
    public static String fileName(Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return id.replace(':', '_').replace('/', '_');
    }

    /**
     * 复制一份实体用于预览/导出，保留装备/变体外观，同时让运行时动画字段归零，
     * 保证同一套参数每次渲染结果一致。
     */
    public static Entity copyOf(Entity source) {
        if (source == null) {
            return null;
        }
        Entity copy;
        try {
            copy = source.getType().create(source.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        } catch (Throwable ignored) {
            return null;
        }
        if (copy == null) {
            return null;
        }
        try {
            TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
            source.saveWithoutId(output);
            CompoundTag tag = output.buildResult();
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, source.registryAccess(), tag);
            copy.load(input);
        } catch (Throwable ignored) {
            // NBT 复制失败时退化为裸实体，至少保证能渲染
        }
        copy.setPos(source.getX(), source.getY(), source.getZ());
        copy.setYRot(source.getYRot());
        copy.setXRot(source.getXRot());
        return copy;
    }
}
