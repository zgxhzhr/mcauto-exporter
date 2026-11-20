package io.github.zgxhzhr.mcauto.exporter.command;

import io.github.zgxhzhr.mcauto.exporter.gui.ExporterScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;

public final class ExportCommand {
    private ExportCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("mcauto")
                        .executes(context -> {
                            Minecraft.getInstance().setScreen(new ExporterScreen());
                            return 1;
                        })));
    }
}
