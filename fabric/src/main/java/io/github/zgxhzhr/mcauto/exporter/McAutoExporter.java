package io.github.zgxhzhr.mcauto.exporter;

import io.github.zgxhzhr.mcauto.exporter.command.ExportCommand;
import io.github.zgxhzhr.mcauto.exporter.config.ExporterConfig;
import io.github.zgxhzhr.mcauto.exporter.core.AutoExport;
import io.github.zgxhzhr.mcauto.exporter.core.ExportService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public final class McAutoExporter implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        ModItems.register();
        ModCreativeTabs.register();
    }

    @Override
    public void onInitializeClient() {
        ExporterConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ExportService.get().tick();
            AutoExport.tick();
        });
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> ExportService.get().onHudRender(graphics));
        ExportCommand.register();
        ExporterLog.LOG.info("mcauto exporter ready");
    }
}
