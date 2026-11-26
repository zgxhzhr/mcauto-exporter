package io.github.zgxhzhr.meowarchive.exporter.platform.services;

import net.minecraft.resources.ResourceLocation;
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
    default ResourceLocation fluidStillTexture(Fluid fluid) {
        return null;
    }

    /** 流体流动贴图（立方体侧面用）；取不到返回 null。 */
    default ResourceLocation fluidFlowingTexture(Fluid fluid) {
        return null;
    }

    /** 流体着色（ARGB）；未知返回 -1。 */
    default int fluidTint(Fluid fluid) {
        return -1;
    }
}
