package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * 本模组独立的创造模式物品栏。
 */
public final class ModCreativeTabs {

    public static final ResourceKey<CreativeModeTab> MAIN = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(ModItems.MOD_ID, "main"));

    private ModCreativeTabs() {
    }

    public static CreativeModeTab create() {
        // 26.2 起 CreativeModeTab.Output 变为 protected，触发式填充改在各加载器模块里做。
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.meowarchive.main"))
                .icon(() -> new ItemStack(ModItems.MIAO_WU_JI))
                .build();
    }

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN, create());
    }
}
