package io.github.zgxhzhr.meowarchive.exporter.platform.services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;

import java.nio.file.Path;
import java.util.List;

/**
 * 各加载器的差异点。common 只依赖这个接口，具体实现由各平台模块通过 ServiceLoader 提供。
 */
public interface IPlatformHelper {

    /** 游戏根目录（客户端为 .minecraft 或开发环境的 run 目录）。 */
    Path gameDir();

    /** 配置目录。 */
    Path configDir();

    /** 已加载的模组 id 列表。 */
    List<String> loadedModIds();

    /** 模组显示名；取不到时返回 id。 */
    String modName(String modId);

    /** 是否开发环境。 */
    default boolean isDevelopmentEnvironment() {
        return false;
    }

    /** 流体的本地化键（NeoForge 为 fluid_type.&lt;ns&gt;.&lt;path&gt;）；取不到返回空串。 */
    default String fluidTranslationKey(Fluid fluid) {
        return "";
    }

    /** 流体静止贴图；取不到返回 null。 */
    default Identifier fluidStillTexture(Fluid fluid) {
        return textureOf(fluidModel(fluid), true);
    }

    /** 流体流动贴图（立方体侧面用）；取不到返回 null。 */
    default Identifier fluidFlowingTexture(Fluid fluid) {
        return textureOf(fluidModel(fluid), false);
    }

    /** 流体着色（ARGB）；未知返回 -1。 */
    default int fluidTint(Fluid fluid) {
        try {
            FluidModel model = fluidModel(fluid);
            if (model == null || model.tintSource() == null) {
                return -1;
            }
            return model.tintSource().color(fluid.defaultFluidState().createLegacyBlock());
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 26.x 起流体贴图改挂到原版流体模型上，直接从 FluidStateModelSet 取。 */
    private static FluidModel fluidModel(Fluid fluid) {
        try {
            return Minecraft.getInstance().getModelManager().getFluidStateModelSet()
                    .get(fluid.defaultFluidState());
        } catch (Throwable e) {
            return null;
        }
    }

    private static Identifier textureOf(FluidModel model, boolean still) {
        try {
            if (model == null) {
                return null;
            }
            Material.Baked material = still ? model.stillMaterial() : model.flowingMaterial();
            if (material == null) {
                return null;
            }
            TextureAtlasSprite sprite = material.sprite();
            if (sprite == null || sprite.contents() == null) {
                return null;
            }
            return sprite.contents().name();
        } catch (Throwable e) {
            return null;
        }
    }
}
