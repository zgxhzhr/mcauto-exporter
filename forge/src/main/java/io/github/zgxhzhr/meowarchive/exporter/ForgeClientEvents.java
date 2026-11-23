package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.command.Commands;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ForgeClientEvents {
    private ForgeClientEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ForgeClientEvents::clientSetup);
        MinecraftForge.EVENT_BUS.addListener(ForgeClientEvents::clientTick);
        MinecraftForge.EVENT_BUS.addListener(ForgeClientEvents::renderGui);
        MinecraftForge.EVENT_BUS.addListener(ForgeClientEvents::registerClientCommands);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        ExporterConfig.load();
        ExporterLog.LOG.info("Meow Archive Exporter ready (Forge)");
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ExportService.get().tick();
        AutoExport.tick();
    }

    private static void renderGui(RenderGameOverlayEvent.Post event) {
        // 1.18.2 只有 RenderGameOverlayEvent，逐个元素触发；只在 ALL 阶段画一次
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        ExportService.get().onHudRender(event.getMatrixStack());
    }

    private static void registerClientCommands(RegisterCommandsEvent event) {
        // 1.16.5 没有独立的客户端指令事件，用通用指令事件注册；切界面必须回主线程。
        event.getDispatcher().register(Commands.literal("meowarchive").executes(context -> {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ExporterScreen()));
            return 1;
        }));
    }
}
