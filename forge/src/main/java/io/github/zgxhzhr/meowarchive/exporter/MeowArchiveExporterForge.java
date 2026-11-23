package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.util.NonNullList;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(ModItems.MOD_ID)
public final class MeowArchiveExporterForge {
    /**
     * 1.19.2 没有创造模式标签页注册表，Forge 允许用只有一个名称的构造函数创建标签页，
     * 标签内容由 fillItemList 自己填充。
     */
    public static final ItemGroup TAB = new ItemGroup(ModItems.MOD_ID + ".main") {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(ModItems.MIAO_WU_JI);
        }

        @Override
        public void fillItemList(NonNullList<ItemStack> items) {
            items.add(new ItemStack(ModItems.MIAO_WU_JI));
        }
    };

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ModItems.MOD_ID);

    public MeowArchiveExporterForge() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register("miao_wu_ji", () -> ModItems.MIAO_WU_JI);
        ITEMS.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientEvents.register(bus);
        }
    }
}
