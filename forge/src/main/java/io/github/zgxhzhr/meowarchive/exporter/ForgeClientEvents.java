package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ForgeClientEvents {
    private ForgeClientEvents() {
    }

    public static void register(BusGroup bus) {
        FMLClientSetupEvent.getBus(bus).addListener(ForgeClientEvents::clientSetup);
        AddGuiOverlayLayersEvent.BUS.addListener(ForgeClientEvents::addOverlayLayers);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            ExportService.get().tick();
            AutoExport.tick();
        });
        RegisterClientCommandsEvent.BUS.addListener(ForgeClientEvents::registerClientCommands);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (Forge)");
    }

    private static void addOverlayLayers(AddGuiOverlayLayersEvent event) {
        // HUD 是分层绘制，追加一层用于推进导出任务
        event.getLayeredDraw().add(
                Identifier.fromNamespaceAndPath(ModItems.MOD_ID, "export_hud"),
                (graphics, deltaTracker) -> ExportService.get().onHudRender(graphics));
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meowarchive").executes(context -> {
            Minecraft.getInstance().setScreen(new ExporterScreen());
            return 1;
        }));
    }
}
