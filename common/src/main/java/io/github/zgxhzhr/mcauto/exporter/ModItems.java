package io.github.zgxhzhr.mcauto.exporter;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public final class ModItems {
    public static final String MOD_ID = "mcauto_exporter";

    public static final Item MIAO_WU_JI = new ExporterItem(new Item.Properties().stacksTo(1));

    private ModItems() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "miao_wu_ji"), MIAO_WU_JI);
    }
}
