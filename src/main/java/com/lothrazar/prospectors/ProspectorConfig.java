package com.lothrazar.prospectors;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/prospectors-common.toml}: the 1.12 {@code prospectors.cfg} with the same five sections and the same
 * keys, value ranges and defaults ({@code ItemProspector.syncConfig}). Out of range numbers are reset to the default
 * by Forge when the file is loaded.
 */
public final class ProspectorConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ForgeConfigSpec SPEC;
    private static final Map<ProspectorTier, Tier> TIERS = new EnumMap<>(ProspectorTier.class);

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        for (ProspectorTier tier : ProspectorTier.values()) {
            TIERS.put(tier, new Tier(tier, builder));
        }
        SPEC = builder.build();
    }

    private ProspectorConfig() {}

    public static Tier get(ProspectorTier tier) {
        return TIERS.get(tier);
    }

    /**
     * Drops the parsed block lists after the file was (re)loaded; they are parsed again on next use.
     */
    static void invalidate() {
        for (Tier tier : TIERS.values()) {
            tier.matcher = null;
        }
    }

    /**
     * Parses every block list now so problems in the file are logged when the server starts, not on first use.
     */
    static void parseAll() {
        for (Tier tier : TIERS.values()) {
            tier.matcher();
        }
    }

    static void warnUnknownTags() {
        for (Tier tier : TIERS.values()) {
            tier.matcher().warnUnknownTags(tier.tier.getName(), LOGGER);
        }
    }

    public static final class Tier {

        public static final int DEFAULT_DURABILITY = 200;

        private final ProspectorTier tier;
        public final ForgeConfigSpec.BooleanValue isBlacklist;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> blockList;
        public final ForgeConfigSpec.IntValue cooldown;
        public final ForgeConfigSpec.IntValue durability;
        public final ForgeConfigSpec.IntValue range;
        private volatile BlockMatcher matcher;
        /**
         * Durability received from the server this client is connected to (0: none), so the durability bar matches
         * the server's config, see {@link com.lothrazar.prospectors.network.SyncDurabilityPacket}.
         */
        private volatile int syncedDurability;

        private Tier(ProspectorTier tier, ForgeConfigSpec.Builder builder) {
            this.tier = tier;
            builder.push(tier.getName());
            isBlacklist = builder
                    .comment("True means this is a blacklist: ignore whats listed. False means its a whitelist: only print whats listed.")
                    .define("IsBlacklist", false);
            blockList = builder
                    .comment("List of blocks that the Prospector knows about.",
                            "Entries: a block ID ('minecraft:diamond_ore', 'othermod:custom_ore'),",
                            "a block state ('minecraft:redstone_ore[lit=true]', replaces 1.12 'id/meta' entries)",
                            "or a block tag ('#forge:ores/diamond'). Unknown blocks and tags are logged and skipped.")
                    // Every element is accepted here and checked by BlockMatcher: Forge would otherwise reset the
                    // whole list to the default because of a single bad entry.
                    .defineListAllowEmpty(List.of("ProspectorBlockList"), () -> new ArrayList<>(tier.getDefaultBlocks()), o -> o instanceof String);
            cooldown = builder
                    .comment("Time delay per use (ticks); zero to disable")
                    .defineInRange("cooldown", 10, 0, 256);
            durability = builder
                    .comment("Durability: number of uses")
                    .defineInRange("durability", DEFAULT_DURABILITY, 1, 65536);
            range = builder
                    .comment("Search range")
                    .defineInRange("range", 32, 1, 256);
            builder.pop();
        }

        public BlockMatcher matcher() {
            BlockMatcher result = matcher;
            if (result == null) {
                result = BlockMatcher.parse(tier.getName(), blockList.get(), LOGGER);
                boolean blacklist = isBlacklist.get();
                LOGGER.info("[{}] {} with {}, range {}, cooldown {}, durability {}", tier.getName(),
                        blacklist ? "blacklist" : "whitelist", result.describe(), range.get(), cooldown.get(), durability.get());
                if (result.isEmpty()) {
                    LOGGER.warn(blacklist
                            ? "[{}] blacklist with no usable entries: the prospector reports every block that is not air"
                            : "[{}] whitelist with no usable entries: the prospector never finds anything", tier.getName());
                }
                matcher = result;
            }
            return result;
        }

        public int getDurability() {
            int synced = syncedDurability;
            if (synced > 0) {
                return synced;
            }
            // Item stacks can be asked for their durability before the config file is loaded
            return SPEC.isLoaded() ? durability.get() : DEFAULT_DURABILITY;
        }

        public void setSyncedDurability(int durability) {
            this.syncedDurability = durability;
        }
    }
}
