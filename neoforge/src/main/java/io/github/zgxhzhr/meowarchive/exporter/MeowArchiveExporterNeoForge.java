package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(ModItems.MOD_ID)
public final class MeowArchiveExporterNeoForge {
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ModItems.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModItems.MOD_ID);

    public MeowArchiveExporterNeoForge(IEventBus bus) {
        ITEMS.register("miao_wu_ji", () -> ModItems.MIAO_WU_JI);
        TABS.register("main", ModCreativeTabs::create);
        ITEMS.register(bus);
        TABS.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForgeClientEvents.register(bus);
        }
    }
}
