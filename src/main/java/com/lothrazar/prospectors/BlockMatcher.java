package com.lothrazar.prospectors;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code ProspectorBlockList}. Parsing happens once per config load, so the search only does set lookups.
 * <p>
 * Accepted entries:
 * <ul>
 * <li>{@code minecraft:diamond_ore}: every state of that block (1.12: an entry without {@code /meta});</li>
 * <li>{@code minecraft:redstone_ore[lit=true]}: only states with these property values (the 1.20 replacement
 * for 1.12's {@code /meta} entries);</li>
 * <li>{@code #forge:ores/diamond}: every block in that block tag;</li>
 * <li>legacy 1.12 entries ({@code minecraft:redstone_ore/1}, {@code minecraft:quartz_ore}): accepted with a
 * warning, see {@link #parseBlockEntry}.</li>
 * </ul>
 * Entries that cannot be resolved (unknown block, syntax error) are logged and skipped, never fatal.
 */
public final class BlockMatcher {

    private static final Pattern LEGACY_META = Pattern.compile("^([^\\[\\]]+)/(\\d+)$");
    /**
     * 1.12 block IDs of ore-like blocks that were renamed by the 1.13 flattening.
     */
    private static final Map<String, String> LEGACY_RENAMES = Map.of(
            "minecraft:quartz_ore", "minecraft:nether_quartz_ore",
            "minecraft:lit_redstone_ore", "minecraft:redstone_ore[lit=true]");

    private final Set<Block> blocks;
    private final List<TagKey<Block>> tags;
    private final List<StatePredicate> states;

    private BlockMatcher(Set<Block> blocks, List<TagKey<Block>> tags, List<StatePredicate> states) {
        this.blocks = blocks;
        this.tags = tags;
        this.states = states;
    }

    public boolean matches(BlockState state) {
        if (blocks.contains(state.getBlock())) {
            return true;
        }
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) {
                return true;
            }
        }
        for (StatePredicate predicate : states) {
            if (predicate.test(state)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && tags.isEmpty() && states.isEmpty();
    }

    /**
     * Logs a warning for every tag entry that no loaded data pack defines (it then matches nothing). Only meaningful
     * once tags are loaded, so it is called when the server has started and after {@code /reload}.
     */
    public void warnUnknownTags(String owner, Logger logger) {
        for (TagKey<Block> tag : tags) {
            if (BuiltInRegistries.BLOCK.getTag(tag).isEmpty()) {
                logger.warn("[{}] ProspectorBlockList: block tag '#{}' does not exist (no data pack defines it), it matches nothing", owner, tag.location());
            }
        }
    }

    public String describe() {
        return blocks.size() + " blocks, " + tags.size() + " tags, " + states.size() + " block states";
    }

    public static BlockMatcher parse(String owner, List<? extends String> entries, Logger logger) {
        Set<Block> blocks = new LinkedHashSet<>();
        Set<TagKey<Block>> tags = new LinkedHashSet<>();
        Set<StatePredicate> states = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (!seen.add(entry)) {
                logger.info("[{}] ProspectorBlockList: duplicate entry '{}' ignored", owner, entry);
                continue;
            }
            if (entry.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(entry.substring(1));
                if (id == null) {
                    logger.warn("[{}] ProspectorBlockList: '{}' is not a valid block tag, skipped", owner, entry);
                } else if (!tags.add(TagKey.create(Registries.BLOCK, id))) {
                    logger.info("[{}] ProspectorBlockList: duplicate entry '{}' ignored", owner, entry);
                }
                continue;
            }
            BlockStateParser.BlockResult result = parseBlockEntry(owner, entry, logger);
            if (result == null) {
                continue;
            }
            boolean added = result.properties().isEmpty()
                    ? blocks.add(result.blockState().getBlock())
                    : states.add(new StatePredicate(result.blockState(), result.properties()));
            if (!added) {
                logger.info("[{}] ProspectorBlockList: '{}' repeats an earlier entry, ignored", owner, entry);
            }
        }
        return new BlockMatcher(Set.copyOf(blocks), List.copyOf(tags), List.copyOf(states));
    }

    /**
     * Parses {@code namespace:path} or {@code namespace:path[property=value,...]} with the vanilla block state parser
     * (the same syntax as {@code /setblock}). 1.12 entries are translated:
     * <ul>
     * <li>{@code id/meta}: block metadata no longer exists (the 1.13 flattening turned metadata variants into
     * separate block IDs or block state properties), so the entry is read as the whole block {@code id} and a
     * warning suggests the exact modern ID or the {@code [property=value]} syntax;</li>
     * <li>renamed vanilla ore IDs ({@link #LEGACY_RENAMES}) are mapped to their 1.20.1 equivalent.</li>
     * </ul>
     */
    private static BlockStateParser.BlockResult parseBlockEntry(String owner, String entry, Logger logger) {
        String text = entry;
        Matcher legacy = LEGACY_META.matcher(text);
        if (legacy.matches() && ResourceLocation.tryParse(legacy.group(1)) != null) {
            String base = legacy.group(1);
            logger.warn("[{}] ProspectorBlockList: '{}' uses 1.12 metadata, which no longer exists; it is read as the whole block '{}'. "
                    + "Use the 1.20 block ID or a block state such as 'minecraft:redstone_ore[lit=true]' instead", owner, entry, base);
            text = base;
        }
        String renamed = LEGACY_RENAMES.get(text);
        if (renamed != null) {
            logger.warn("[{}] ProspectorBlockList: '{}' is a 1.12 block ID, read as '{}'", owner, entry, renamed);
            text = renamed;
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false);
        } catch (CommandSyntaxException e) {
            logger.warn("[{}] ProspectorBlockList: '{}' skipped: {}", owner, entry, e.getMessage());
            return null;
        }
    }

    /**
     * A block plus the property values an entry spelled out; other properties may have any value.
     */
    private record StatePredicate(Block block, Map<Property<?>, Comparable<?>> properties) {

        StatePredicate(BlockState state, Map<Property<?>, Comparable<?>> properties) {
            this(state.getBlock(), Map.copyOf(properties));
        }

        boolean test(BlockState state) {
            if (!state.is(block)) {
                return false;
            }
            for (Map.Entry<Property<?>, Comparable<?>> property : properties.entrySet()) {
                if (!state.getValue(property.getKey()).equals(property.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }
}
