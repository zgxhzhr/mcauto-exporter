package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(value = ModItems.MOD_ID, dist = Dist.CLIENT)
public final class MeowArchiveExporterNeoForge {

    public MeowArchiveExporterNeoForge(IEventBus modBus) {
        modBus.addListener(this::register);
        modBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::renderGui);
        NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
    }

    private void register(RegisterEvent event) {
        event.register(Registries.ITEM, helper ->
                helper.register(ResourceLocation.fromNamespaceAndPath(ModItems.MOD_ID, "miao_wu_ji"),
                        ModItems.MIAO_WU_JI));
        event.register(Registries.CREATIVE_MODE_TAB, helper ->
                helper.register(ModCreativeTabs.MAIN, ModCreativeTabs.create()));
    }

    private void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (NeoForge)");
    }

    private void clientTick(ClientTickEvent.Post event) {
        ExportService.get().tick();
        AutoExport.tick();
    }

    private void renderGui(RenderGuiEvent.Post event) {
        ExportService.get().onHudRender(event.getGuiGraphics());
    }

    private void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meowarchive").executes(context -> {
            Minecraft.getInstance().setScreen(new ExporterScreen());
            return 1;
        }));
    }
}
