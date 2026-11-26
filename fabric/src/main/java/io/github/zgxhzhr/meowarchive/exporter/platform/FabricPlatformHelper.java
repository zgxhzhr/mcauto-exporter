package io.github.zgxhzhr.meowarchive.exporter.platform;

import io.github.zgxhzhr.meowarchive.exporter.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

import java.nio.file.Path;
import java.util.List;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public List<String> loadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId())
                .toList();
    }

    @Override
    public String modName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getName())
                .orElse(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /**
     * Fabric 没有流体翻译键接口，交由 RegistryScanner 按 fluid / block / 桶物品的候选键猜测。
     */
    @Override
    public String fluidTranslationKey(Fluid fluid) {
        return "";
    }

    @Override
    public ResourceLocation fluidStillTexture(Fluid fluid) {
        return spriteName(fluid, 0);
    }

    @Override
    public ResourceLocation fluidFlowingTexture(Fluid fluid) {
        return spriteName(fluid, 1);
    }

    /** 1.20.1 的 FluidRenderHandler 只暴露贴图集精灵，从精灵名还原纹理 id。 */
    private static ResourceLocation spriteName(Fluid fluid, int index) {
        try {
            FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
            if (handler == null) {
                return null;
            }
            TextureAtlasSprite[] sprites = handler.getFluidSprites(null, null, fluid.defaultFluidState());
            if (sprites == null || sprites.length <= index || sprites[index] == null) {
                return null;
            }
            return sprites[index].contents().name();
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public int fluidTint(Fluid fluid) {
        try {
            FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
            if (handler == null) {
                return -1;
            }
            int color = handler.getFluidColor(null, null, fluid.defaultFluidState());
            if (color < 0) {
                return -1;
            }
            // 部分处理器（如原版水）只返回 RGB，补齐不透明 alpha
            return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
        } catch (Throwable e) {
            return -1;
        }
    }
}
