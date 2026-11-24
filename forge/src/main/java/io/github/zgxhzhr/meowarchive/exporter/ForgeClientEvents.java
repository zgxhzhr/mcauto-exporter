package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
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
        MinecraftForge.EVENT_BUS.addListener(ForgeClientEvents::clientChat);
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

    /**
     * 1.17.1 的 Forge 还没有 RegisterClientCommandsEvent（1.18+ 才有），
     * 这里改为拦截聊天输入里的 /meowarchive，效果与客户端命令一致。
     */
    private static void clientChat(ClientChatEvent event) {
        if (!"/meowarchive".equals(event.getMessage().trim())) {
            return;
        }
        event.setCanceled(true);
        Minecraft.getInstance().setScreen(new ExporterScreen());
    }
}
