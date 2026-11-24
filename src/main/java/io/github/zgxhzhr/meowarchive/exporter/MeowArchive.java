package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.client.ClientEvents;
import io.github.zgxhzhr.meowarchive.exporter.client.CommandMeowArchive;
import net.minecraft.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;

/**
 * 客户端专用的内容导出器。只注册一个物品、一个命令与若干客户端事件，
 * 不参与服务端逻辑。
 */
@Mod(MeowArchive.MOD_ID)
public final class MeowArchive {
    public static final String MOD_ID = "meowarchive";

    /** 1.13 的物品登记走模组事件总线上的 RegistryEvent.Register<Item>。 */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        private Registrar() {
        }

        @SubscribeEvent
        public static void onRegisterItems(RegistryEvent.Register<Item> event) {
            ModItems.register(event.getRegistry());
        }
    }

    /** 客户端事件订阅只在物理客户端加载，避免专用服务器触碰客户端类。 */
    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ClientSetup {
        private ClientSetup() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ClientEvents.register();
        }
    }

    /** 命令在集成服务器启动时注册，单人游戏中即可使用。 */
    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class ServerCommands {
        private ServerCommands() {
        }

        @SubscribeEvent
        public static void onServerStarting(FMLServerStartingEvent event) {
            CommandMeowArchive.register(event.getCommandDispatcher());
        }
    }
}
