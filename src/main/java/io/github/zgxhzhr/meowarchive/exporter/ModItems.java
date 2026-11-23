package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class ModItems {
    public static final String MOD_ID = MeowArchive.MOD_ID;

    /**
     * 自定义创造物品栏。1.12.2 的标题键是 "itemGroup." + 标签，
     * 所以标签取 "meowarchive.main"，对应语言键 itemGroup.meowarchive.main，与其它版本一致。
     */
    public static final CreativeTabs TAB = new CreativeTabs(MOD_ID + ".main") {
        @Override
        public ItemStack getTabIconItem() {
            return new ItemStack(MIAO_WU_JI);
        }
    };

    public static final Item MIAO_WU_JI = new ExporterItem();

    static {
        MIAO_WU_JI.setCreativeTab(TAB);
    }

    private ModItems() {
    }

    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Registrar());
    }

    /** 1.12.2 的物品登记走 RegistryEvent.Register<Item>。 */
    public static final class Registrar {
        @SubscribeEvent
        public void onRegisterItems(RegistryEvent.Register<Item> event) {
            event.getRegistry().register(ModItems.MIAO_WU_JI);
        }
    }
}
