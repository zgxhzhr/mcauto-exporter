package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ForgeClientEvents {
    private ForgeClientEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ForgeClientEvents::clientSetup);
        bus.addListener(ForgeClientEvents::addGuiLayer);
        MinecraftForge.EVENT_BUS.addListener(ForgeClientEvents::clientTick);
        MinecraftForge.EVENT_BUS.addListener(ForgeClientEvents::registerClientCommands);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (Forge)");
    }

    private static void addGuiLayer(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().add(
                ResourceLocation.fromNamespaceAndPath("meowarchive", "export_hud"),
                (graphics, partialTick) -> ExportService.get().onHudRender(graphics));
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
