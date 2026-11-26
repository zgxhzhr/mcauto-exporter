package io.github.zgxhzhr.meowarchive.exporter.platform.services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
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

    /**
     * 流体静止贴图；取不到返回 null。
     * 26.x 起贴图不再挂在 FluidType 客户端扩展上，而是由 {@code FluidStateModelSet} 烘焙的
     * {@link FluidModel} 提供，这里直接读取 sprite 名（形如 {@code minecraft:block/water_still}）。
     */
    default Identifier fluidStillTexture(Fluid fluid) {
        return fluidSprite(fluid, true);
    }

    /** 流体流动贴图（立方体侧面用）；取不到返回 null。 */
    default Identifier fluidFlowingTexture(Fluid fluid) {
        return fluidSprite(fluid, false);
    }

    /** 流体输出着色（ARGB）；未知返回 -1。 */
    default int fluidTint(Fluid fluid) {
        try {
            FluidModel model = fluidModel(fluid);
            BlockTintSource tint = model == null ? null : model.tintSource();
            return tint == null ? -1 : tint.color(fluid.defaultFluidState().createLegacyBlock());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static FluidModel fluidModel(Fluid fluid) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null) {
                return null;
            }
            var models = mc.getModelManager().getFluidStateModelSet();
            return models == null ? null : models.get(fluid.defaultFluidState());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Identifier fluidSprite(Fluid fluid, boolean still) {
        try {
            FluidModel model = fluidModel(fluid);
            if (model == null) {
                return null;
            }
            Material.Baked baked = still ? model.stillMaterial() : model.flowingMaterial();
            if (baked == null || baked.sprite() == null) {
                return null;
            }
            Identifier name = baked.sprite().contents().name();
            // 未注册流体模型时给的是 missingno，不能当真实贴图
            if (name == null || name.getPath().contains("missingno")) {
                return null;
            }
            return name;
        } catch (Throwable t) {
            return null;
        }
    }
}
