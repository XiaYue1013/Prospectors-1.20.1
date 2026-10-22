package com.lothrazar.prospectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The search of {@code ItemProspector.onItemUse} (1.12): starting at the clicked block itself, {@code range} blocks
 * in a straight line in the direction opposite to the clicked face, i.e. into the block away from the player.
 * Blocks shown by the list are counted per name.
 * <p>
 * Differences required by 1.20 (they do not change what is found in loaded terrain):
 * <ul>
 * <li>the line stops at the world's build height limits instead of reading air beyond them;</li>
 * <li>blocks in chunks that are not loaded are skipped instead of loading or generating the chunk;</li>
 * <li>air is skipped, which is what the 1.12 code meant to do (its air check compared a block state to a block and
 * never matched, so a blacklist prospector counted air).</li>
 * </ul>
 */
public final class ProspectorSearch {

    private ProspectorSearch() {}

    public static Collection<Found> search(Level level, BlockPos clicked, Direction side, int range, BlockMatcher matcher, boolean isBlacklist) {
        Direction direction = side.getOpposite();
        Map<Object, Found> found = new LinkedHashMap<>();
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
        LevelChunk chunk = null;
        long chunkKey = Long.MIN_VALUE;
        for (int i = 0; i < range; i++) {
            current.setWithOffset(clicked, direction.getStepX() * i, direction.getStepY() * i, direction.getStepZ() * i);
            if (level.isOutsideBuildHeight(current)) {
                // the line only moves away from the clicked block, it cannot come back inside
                break;
            }
            int chunkX = SectionPos.blockToSectionCoord(current.getX());
            int chunkZ = SectionPos.blockToSectionCoord(current.getZ());
            long key = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
            if (key != chunkKey) {
                chunkKey = key;
                // never loads: null when the chunk is not loaded
                chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            }
            if (chunk == null) {
                continue;
            }
            BlockState at = chunk.getBlockState(current);
            if (at.isAir() || matcher.matches(at) == isBlacklist) {
                continue;
            }
            Block block = at.getBlock();
            Item item = block.asItem();
            // 1.12 counted by the display name of the block's item stack; blocks without an item use their own name
            Object group = item == Items.AIR ? block : item;
            found.computeIfAbsent(group, g -> new Found(item == Items.AIR ? block.getName() : new ItemStack(item).getHoverName())).count++;
        }
        return found.values();
    }

    public static final class Found {

        private final Component name;
        private int count;

        Found(Component name) {
            this.name = name;
        }

        public Component getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }
}
