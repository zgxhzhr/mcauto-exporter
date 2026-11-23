package io.github.zgxhzhr.meowarchive.exporter;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public final class ModItems {
    public static final String MOD_ID = "meowarchive";
    public static final ResourceLocation MIAO_WU_JI_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "miao_wu_ji");

    public static final Item MIAO_WU_JI = new ExporterItem(new Item.Properties()
            .stacksTo(1)
            .setId(ResourceKey.create(Registries.ITEM, MIAO_WU_JI_ID)));

    private ModItems() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.ITEM, MIAO_WU_JI_ID, MIAO_WU_JI);
    }
}
