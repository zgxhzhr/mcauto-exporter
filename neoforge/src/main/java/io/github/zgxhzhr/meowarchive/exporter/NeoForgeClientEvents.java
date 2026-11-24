package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;

public final class NeoForgeClientEvents {
    private NeoForgeClientEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(NeoForgeClientEvents::clientSetup);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::clientTick);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::renderGui);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::registerClientCommands);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (NeoForge)");
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ExportService.get().tick();
        AutoExport.tick();
    }

    private static void renderGui(RenderGuiEvent.Post event) {
        ExportService.get().onHudRender(event.getGuiGraphics());
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meowarchive").executes(context -> {
            Minecraft.getInstance().setScreen(new ExporterScreen());
            return 1;
        }));
    }
}
