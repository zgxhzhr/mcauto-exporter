package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(ModItems.MOD_ID)
public final class MeowArchiveExporterForge {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ModItems.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModItems.MOD_ID);

    public MeowArchiveExporterForge() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register("miao_wu_ji", () -> ModItems.MIAO_WU_JI);
        TABS.register("main", ModCreativeTabs::create);
        ITEMS.register(bus);
        TABS.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientEvents.register(bus);
        }
    }
}
