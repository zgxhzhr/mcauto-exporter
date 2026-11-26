package io.github.zgxhzhr.meowarchive.exporter.platform;

import io.github.zgxhzhr.meowarchive.exporter.platform.services.IPlatformHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
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
        return !FMLEnvironment.isProduction();
    }

    /** NeoForge 把流体信息挂在 FluidType 上，比原版多一层。 */
    @Override
    public String fluidTranslationKey(Fluid fluid) {
        try {
            return fluid.getFluidType().getDescriptionId();
        } catch (Throwable e) {
            return "";
        }
    }

    @Override
    public Identifier fluidStillTexture(Fluid fluid) {
        try {
            return IClientFluidTypeExtensions.of(fluid).getStillTexture();
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public Identifier fluidFlowingTexture(Fluid fluid) {
        try {
            return IClientFluidTypeExtensions.of(fluid).getFlowingTexture();
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public int fluidTint(Fluid fluid) {
        try {
            return IClientFluidTypeExtensions.of(fluid).getTintColor();
        } catch (Throwable e) {
            return -1;
        }
    }
}
