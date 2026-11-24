package io.github.zgxhzhr.meowarchive.exporter;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

public final class ModItems {
    public static final String MOD_ID = MeowArchive.MOD_ID;

    /**
     * 自定义创造物品栏。1.7.10 的标题键是 "itemGroup." + 标签，
     * 所以标签取 "meowarchive.main"，对应语言键 itemGroup.meowarchive.main，与其它版本一致。
     */
    public static final CreativeTabs TAB = new CreativeTabs(MOD_ID + ".main") {
        @Override
        public Item getTabIconItem() {
            return MIAO_WU_JI;
        }
    };

    public static final Item MIAO_WU_JI = new ExporterItem();

    private ModItems() {
    }

    public static void register() {
        GameRegistry.registerItem(MIAO_WU_JI, "miao_wu_ji");
    }
}
