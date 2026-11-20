package io.github.zgxhzhr.mcauto.exporter;

import io.github.zgxhzhr.mcauto.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 手持右键打开导出器界面。
 */
public class ExporterItem extends Item {

    public ExporterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            Minecraft.getInstance().setScreen(new ExporterScreen());
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
