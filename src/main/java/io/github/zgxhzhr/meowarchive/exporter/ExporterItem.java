package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.gui.ExporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * 手持右键打开导出器界面。
 */
public class ExporterItem extends Item {

    public ExporterItem() {
        setUnlocalizedName(MeowArchive.MOD_ID + ".miao_wu_ji");
        setMaxStackSize(1);
        setCreativeTab(ModItems.TAB);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) {
            Minecraft.getMinecraft().displayGuiScreen(new ExporterScreen());
        }
        return stack;
    }
}
