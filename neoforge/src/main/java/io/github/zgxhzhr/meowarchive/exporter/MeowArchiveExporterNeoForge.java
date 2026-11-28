package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(value = ModItems.MOD_ID, dist = Dist.CLIENT)
public final class MeowArchiveExporterNeoForge {

    public MeowArchiveExporterNeoForge(IEventBus modBus) {
        modBus.addListener(this::register);
        modBus.addListener(this::buildCreativeTab);
        modBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(this::clientTickPre);
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::renderGui);
        NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
    }

    private void register(RegisterEvent event) {
        event.register(Registries.ITEM, helper ->
                helper.register(ModItems.MIAO_WU_JI_ID, ModItems.MIAO_WU_JI));
        event.register(Registries.CREATIVE_MODE_TAB, helper ->
                helper.register(ModCreativeTabs.MAIN, ModCreativeTabs.create()));
    }

    private void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.MAIN)) {
            event.accept(new ItemStack(ModItems.MIAO_WU_JI));
        }
    }

    private void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (NeoForge)");
    }

    /** 必须早于原版处理选取方块，才能吞掉中键点击。 */
    private void clientTickPre(ClientTickEvent.Pre event) {
        SingleEntityPicker.tick();
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
            Minecraft.getInstance().gui.setScreen(new ExporterScreen());
            return 1;
        }).then(Commands.literal("entity").executes(context -> {
            Minecraft.getInstance().execute(SingleEntityPicker::openForCrosshair);
            return 1;
        })));
    }
}
