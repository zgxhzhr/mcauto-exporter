package io.github.zgxhzhr.meowarchive.exporter.platform;

import io.github.zgxhzhr.meowarchive.exporter.platform.services.IPlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
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
        return !FMLEnvironment.isProduction();
    }

    /**
     * 26.2 起流体的静止/流动贴图与着色从 IClientFluidTypeExtensions 移到了客户端的
     * FluidStateModelSet（原版与 NeoForge 的 RegisterFluidModelsEvent 都往这里注册）。
     */
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
        return materialTexture(fluid, true);
    }

    @Override
    public Identifier fluidFlowingTexture(Fluid fluid) {
        return materialTexture(fluid, false);
    }

    @Override
    public int fluidTint(Fluid fluid) {
        try {
            FluidState state = fluid.defaultFluidState();
            BlockTintSource tint = model(state).tintSource();
            if (tint == null) {
                return -1;
            }
            return tint.color(state.createLegacyBlock());
        } catch (Throwable e) {
            return -1;
        }
    }

    private static Identifier materialTexture(Fluid fluid, boolean still) {
        try {
            FluidModel model = model(fluid.defaultFluidState());
            Material.Baked material = still ? model.stillMaterial() : model.flowingMaterial();
            if (material == null || material.sprite() == null || material.sprite().contents() == null) {
                return null;
            }
            Identifier name = material.sprite().contents().name();
            // 未注册模型时会退回 missingno，交给上层的路径猜测
            if (name == null || name.equals(MissingTextureAtlasSprite.getLocation())) {
                return null;
            }
            return name;
        } catch (Throwable e) {
            return null;
        }
    }

    private static FluidModel model(FluidState state) {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(state);
    }
}
