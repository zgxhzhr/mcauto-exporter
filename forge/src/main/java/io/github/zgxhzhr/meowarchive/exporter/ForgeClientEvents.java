package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ForgeClientEvents {
    private ForgeClientEvents() {
    }

    public static void register(BusGroup bus) {
        FMLClientSetupEvent.getBus(bus).addListener(ForgeClientEvents::clientSetup);
        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> SingleEntityPicker.tick());
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            ExportService.get().tick();
            AutoExport.tick();
        });
        // Forge 58 还没有 GUI 分层事件，用渲染帧结束事件推进导出（与 fabric 的 HUD 回调等价）
        TickEvent.RenderTickEvent.Post.BUS.addListener(event -> ExportService.get().onHudRender(null));
        RegisterClientCommandsEvent.BUS.addListener(ForgeClientEvents::registerClientCommands);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (Forge)");
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meowarchive").executes(context -> {
            Minecraft.getInstance().setScreen(new ExporterScreen());
            return 1;
        }).then(Commands.literal("entity").executes(context -> {
            Minecraft.getInstance().execute(SingleEntityPicker::openForCrosshair);
            return 1;
        })));
    }
}
