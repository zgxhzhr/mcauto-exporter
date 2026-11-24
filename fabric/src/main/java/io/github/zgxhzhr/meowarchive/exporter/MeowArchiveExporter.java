package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class MeowArchiveExporter implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        ModItems.register();
    }

    @Override
    public void onInitializeClient() {
        ExporterConfig.load();
        FabricItemGroupBuilder.create(new ResourceLocation(ModItems.MOD_ID, "main"))
                .icon(() -> new ItemStack(ModItems.MIAO_WU_JI))
                .appendItems(stacks -> stacks.add(new ItemStack(ModItems.MIAO_WU_JI)))
                .build();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ExportService.get().tick();
            AutoExport.tick();
        });
        HudRenderCallback.EVENT.register(tickDelta -> ExportService.get().onHudRender());
        ExporterLog.LOG.info("Meow Archive Exporter ready");
    }
}
