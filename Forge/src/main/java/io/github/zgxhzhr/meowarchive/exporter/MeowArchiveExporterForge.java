package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.CreativeModeTabEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(ModItems.MOD_ID)
public final class MeowArchiveExporterForge {

    private static CreativeModeTab tab;

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ModItems.MOD_ID);

    public MeowArchiveExporterForge() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register("miao_wu_ji", () -> ModItems.MIAO_WU_JI);
        ITEMS.register(bus);
        bus.addListener(MeowArchiveExporterForge::registerCreativeTab);
        bus.addListener(MeowArchiveExporterForge::buildCreativeTab);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientEvents.register(bus);
        }
    }

    private static void registerCreativeTab(CreativeModeTabEvent.Register event) {
        tab = event.registerCreativeModeTab(
                new ResourceLocation(ModItems.MOD_ID, "main"),
                builder -> builder
                        .title(Component.translatable("itemGroup.meowarchive.main"))
                        .icon(() -> new ItemStack(ModItems.MIAO_WU_JI)));
    }

    private static void buildCreativeTab(CreativeModeTabEvent.BuildContents event) {
        if (event.getTab() == tab) {
            event.accept(ModItems.MIAO_WU_JI);
        }
    }
}
