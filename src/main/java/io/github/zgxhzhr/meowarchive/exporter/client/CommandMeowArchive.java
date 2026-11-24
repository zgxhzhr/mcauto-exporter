package io.github.zgxhzhr.meowarchive.exporter.client;

import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端命令 /meowarchive：打开导出界面。
 */
@SideOnly(Side.CLIENT)
public final class CommandMeowArchive extends CommandBase {

    @Override
    public String getCommandName() {
        return "meowarchive";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/meowarchive";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws WrongUsageException {
        if (Minecraft.getMinecraft().thePlayer == null) {
            sender.addChatMessage(new ChatComponentText("请先进入一个世界"));
            return;
        }
        Minecraft.getMinecraft().displayGuiScreen(new ExporterScreen());
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args,
                                        net.minecraft.util.BlockPos pos) {
        return new ArrayList<String>();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }
}
