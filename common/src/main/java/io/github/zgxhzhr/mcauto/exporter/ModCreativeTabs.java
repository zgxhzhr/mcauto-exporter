package io.github.zgxhzhr.mcauto.exporter;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * 本模组独立的创造模式物品栏。
 */
public final class ModCreativeTabs {

    public static final ResourceKey<CreativeModeTab> MAIN = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath(ModItems.MOD_ID, "main"));

    private ModCreativeTabs() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN,
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.mcauto_exporter.main"))
                .icon(() -> new ItemStack(ModItems.MIAO_WU_JI))
                .displayItems((parameters, output) -> output.accept(ModItems.MIAO_WU_JI))
                .build());
    }
}
