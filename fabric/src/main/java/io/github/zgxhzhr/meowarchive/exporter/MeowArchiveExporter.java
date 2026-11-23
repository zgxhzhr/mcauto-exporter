package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.command.ExportCommand;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class MeowArchiveExporter implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        ModItems.register();
        ModCreativeTabs.register();
        CreativeModeTabEvents.modifyOutputEvent(ModCreativeTabs.MAIN)
                .register(output -> output.accept(new ItemStack(ModItems.MIAO_WU_JI)));
    }

    @Override
    public void onInitializeClient() {
        ExporterConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ExportService.get().tick();
            AutoExport.tick();
        });
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(ModItems.MOD_ID, "export_hud"),
                (graphics, deltaTracker) -> ExportService.get().onHudRender(graphics));
        ExportCommand.register();
        ExporterLog.LOG.info("Meow Archive Exporter ready");
    }
}
