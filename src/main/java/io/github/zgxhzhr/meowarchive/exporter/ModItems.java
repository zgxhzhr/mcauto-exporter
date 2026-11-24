package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.IForgeRegistry;

public final class ModItems {
    public static final String MOD_ID = MeowArchive.MOD_ID;

    /**
     * 自定义创造物品栏。1.13 的 getTranslationKey() 会返回 "itemGroup." + 标签，
     * 所以标签取 "meowarchive.main"，对应语言键 itemGroup.meowarchive.main，与其它版本一致。
     */
    public static final ItemGroup TAB = new ItemGroup(MOD_ID + ".main") {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(MIAO_WU_JI);
        }
    };

    public static final Item MIAO_WU_JI = new ExporterItem(
            new Item.Properties().maxStackSize(1).group(TAB));

    private ModItems() {
    }

    public static void register(IForgeRegistry<Item> registry) {
        registry.register(MIAO_WU_JI.setRegistryName(new ResourceLocation(MOD_ID, "miao_wu_ji")));
    }
}
