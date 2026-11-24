package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.client.ClientEvents;
import io.github.zgxhzhr.meowarchive.exporter.client.CommandMeowArchive;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.client.ClientCommandHandler;

/**
 * 客户端专用的内容导出器。只注册一个物品、一个客户端命令与若干客户端事件，
 * 不参与服务端逻辑。
 */
@Mod(modid = MeowArchive.MOD_ID, name = "Meow Archive Exporter", version = "1.0.0",
        acceptableRemoteVersions = "*")
public final class MeowArchive {
    public static final String MOD_ID = "meowarchive";

    @Mod.Instance(MOD_ID)
    public static MeowArchive INSTANCE;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModItems.register();
        if (event.getSide().isClient()) {
            // 1.8.9 的物品模型不会按注册名自动绑定，必须显式登记 inventory 模型位置，
            // 否则会渲染成黑紫的缺失模型方块。
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                    ModItems.MIAO_WU_JI, 0,
                    new net.minecraft.client.resources.model.ModelResourceLocation(
                            MOD_ID + ":miao_wu_ji", "inventory"));
        }
        ClientEvents.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new CommandMeowArchive());
    }
}
