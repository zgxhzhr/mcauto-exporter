package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.client.ClientEvents;
import io.github.zgxhzhr.meowarchive.exporter.client.CommandMeowArchive;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.client.ClientCommandHandler;

/**
 * 客户端专用的内容导出器。只注册一个物品、一个客户端命令与若干客户端事件，
 * 不参与服务端逻辑，因此标记为 clientSideOnly。
 */
@Mod(modid = MeowArchive.MOD_ID, name = "Meow Archive Exporter", version = "1.0.0",
        clientSideOnly = true, acceptableRemoteVersions = "*")
public final class MeowArchive {
    public static final String MOD_ID = "meowarchive";

    @Mod.Instance(MOD_ID)
    public static MeowArchive INSTANCE;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModItems.register();
        ClientEvents.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new CommandMeowArchive());
    }
}
