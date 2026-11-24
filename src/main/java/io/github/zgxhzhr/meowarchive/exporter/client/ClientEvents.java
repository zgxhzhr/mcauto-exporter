package io.github.zgxhzhr.meowarchive.exporter.client;

import io.github.zgxhzhr.meowarchive.exporter.core.AutoExport;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * 客户端事件入口：每帧导出推进、自动导出。
 */
@SideOnly(Side.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    public static void register() {
        ClientEvents handler = new ClientEvents();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance().bus().register(handler);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AutoExport.tick();
    }

    /**
     * HUD 阶段绘制：此时是 2D 正交投影，适合做离屏渲染。
     * 每帧只处理一个目标，避免卡顿。
     */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        ExportService.get().onHudRender();
    }
}
