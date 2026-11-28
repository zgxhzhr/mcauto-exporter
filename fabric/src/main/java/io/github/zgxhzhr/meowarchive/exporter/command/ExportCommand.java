package io.github.zgxhzhr.meowarchive.exporter.command;

import io.github.zgxhzhr.meowarchive.exporter.SingleEntityPicker;
import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;

public final class ExportCommand {
    private ExportCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("meowarchive")
                        .executes(context -> {
                            Minecraft.getInstance().setScreen(new ExporterScreen());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("entity").executes(context -> {
                            SingleEntityPicker.openForCrosshair();
                            return 1;
                        }))));
    }
}
