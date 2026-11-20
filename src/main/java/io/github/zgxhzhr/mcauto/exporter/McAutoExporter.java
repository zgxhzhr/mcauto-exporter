package io.github.zgxhzhr.mcauto.exporter;

import io.github.zgxhzhr.mcauto.exporter.command.ExportCommand;
import io.github.zgxhzhr.mcauto.exporter.config.ExporterConfig;
import io.github.zgxhzhr.mcauto.exporter.core.ExportService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McAutoExporter implements ClientModInitializer {
    public static final String MOD_ID = "mcauto_exporter";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ExporterConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ExportService.get().tick());
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> ExportService.get().onHudRender(graphics));
        ExportCommand.register();
        LOG.info("mcauto exporter ready");
    }
}
