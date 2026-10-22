package com.lothrazar.prospectors.dev;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Development only (excluded from the release jar): stands in for an ore added by another mod, in its own namespace
 * {@code prospectorstest} and in the block tag {@code #forge:ores/mythril} (data/forge, also excluded).
 */
public final class TestOreMod {

    public static final String NAMESPACE = "prospectorstest";
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, NAMESPACE);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, NAMESPACE);

    public static final RegistryObject<Block> MYTHRIL_ORE = BLOCKS.register("mythril_ore", () -> new Block(BlockBehaviour.Properties.copy(Blocks.EMERALD_ORE)));
    public static final RegistryObject<Item> MYTHRIL_ORE_ITEM = ITEMS.register("mythril_ore", () -> new BlockItem(MYTHRIL_ORE.get(), new Item.Properties()));

    private TestOreMod() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}
