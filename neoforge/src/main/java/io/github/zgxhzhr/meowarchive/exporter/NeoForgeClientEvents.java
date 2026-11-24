package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class NeoForgeClientEvents {
    private NeoForgeClientEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(NeoForgeClientEvents::clientSetup);
        bus.addListener(NeoForgeClientEvents::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::clientTick);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::registerClientCommands);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (NeoForge)");
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        // HUD 分层绘制：追加一层用于推进导出任务
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("meowarchive", "export_hud"),
                (graphics, deltaTracker) -> ExportService.get().onHudRender(graphics));
    }

    private static void clientTick(ClientTickEvent.Post event) {
        ExportService.get().tick();
        AutoExport.tick();
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meowarchive").executes(context -> {
            Minecraft.getInstance().setScreen(new ExporterScreen());
            return 1;
        }));
    }
}
