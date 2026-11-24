package io.github.zgxhzhr.meowarchive.exporter.platform;

import io.github.zgxhzhr.meowarchive.exporter.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.List;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public List<String> loadedModIds() {
        return ModList.get().getMods().stream()
                .map(IModInfo::getModId)
                .toList();
    }

    @Override
    public String modName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        // NeoForge 21.5 / 21.8 用的是 FancyModLoader 7~9，FMLEnvironment 还是静态字段形式
        return !FMLEnvironment.production;
    }
}
