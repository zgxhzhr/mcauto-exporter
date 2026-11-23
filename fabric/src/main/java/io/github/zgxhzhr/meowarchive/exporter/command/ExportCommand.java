package io.github.zgxhzhr.meowarchive.exporter.command;

import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

public final class ExportCommand {
    private ExportCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("meowarchive")
                        .executes(context -> {
                            Minecraft.getInstance().setScreen(new ExporterScreen());
                            return 1;
                        })));
    }
}
