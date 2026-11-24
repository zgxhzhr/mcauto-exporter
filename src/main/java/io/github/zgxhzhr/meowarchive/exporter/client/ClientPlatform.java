package io.github.zgxhzhr.meowarchive.exporter.client;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.10 客户端环境信息：游戏目录、配置目录、已加载模组。
 */
public final class ClientPlatform {

    private ClientPlatform() {
    }

    public static Path gameDir() {
        return Minecraft.getMinecraft().mcDataDir.toPath();
    }

    public static Path configDir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config").toPath();
    }

    public static List<String> loadedModIds() {
        List<String> out = new ArrayList<>();
        try {
            for (ModContainer mod : Loader.instance().getModList()) {
                out.add(mod.getModId());
            }
        } catch (Throwable t) {
            ExporterLog.LOG.warn("读取模组列表失败: {}", t.toString());
        }
        return out;
    }

    public static String modName(String modId) {
        try {
            ModContainer mod = Loader.instance().getIndexedModList().get(modId);
            if (mod != null && mod.getName() != null && !mod.getName().isEmpty()) {
                return mod.getName();
            }
        } catch (Throwable ignored) {
        }
        return modId;
    }

    public static boolean isDevelopmentEnvironment() {
        // 开发环境中运行目录下会有 gradle 生成的临时标记文件
        return new File(".").getAbsoluteFile().getName().equals("run");
    }
}
