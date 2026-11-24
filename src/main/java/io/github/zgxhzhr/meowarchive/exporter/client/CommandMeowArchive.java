package io.github.zgxhzhr.meowarchive.exporter.client;

import com.mojang.brigadier.CommandDispatcher;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.util.text.TextComponentString;

/**
 * 命令 /meowarchive：打开导出界面。1.13 使用 Brigadier，命令注册在集成服务器上。
 */
public final class CommandMeowArchive {

    private CommandMeowArchive() {
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("meowarchive").executes(context -> {
            CommandSource source = context.getSource();
            if (Minecraft.getInstance().player == null) {
                source.sendErrorMessage(new TextComponentString("请先进入一个世界"));
                return 0;
            }
            Minecraft.getInstance().addScheduledTask(() ->
                    Minecraft.getInstance().displayGuiScreen(new ExporterScreen()));
            return 1;
        }));
    }
}
