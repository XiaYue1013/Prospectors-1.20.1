package com.lothrazar.prospectors;

import java.util.List;
import java.util.Locale;

/**
 * The five tiers of the original mod ({@code Prospectors.Types} in 1.12). The registry name and the config section
 * are both {@code "prospector_" + name().toLowerCase()}, exactly as before.
 * <p>
 * The default block lists are the 1.12 lists ({@code Prospectors.syncAllConfig}) moved to 1.20.1 block IDs:
 * <ul>
 * <li>every listed ore also gets its deepslate variant;</li>
 * <li>{@code minecraft:sand} matched both sand and red sand in 1.12 (metadata was ignored), so red sand is listed;</li>
 * <li>{@code minecraft:quartz_ore} is now {@code minecraft:nether_quartz_ore};</li>
 * <li>{@code minecraft:redstone_ore/0} and {@code /1} become {@code minecraft:redstone_ore}, which covers both the
 * unlit and the lit ({@code lit=true}) state;</li>
 * <li>copper ore (new in 1.17, mined like iron) is added from the first tier that knows iron ore;</li>
 * <li>nether gold ore (new in 1.16) is added from the first tier that knows gold ore.</li>
 * </ul>
 * Duplicate entries of the 1.12 defaults (iron ore was listed twice in the two top tiers) are left out.
 */
public enum ProspectorTier {

    LOWEST(List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:gravel",
            "minecraft:sand", "minecraft:red_sand")),
    LOW(List.of(
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore")),
    MED(List.of(
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore")),
    HIGH(List.of(
            "minecraft:nether_quartz_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore")),
    BEST(List.of(
            "minecraft:nether_quartz_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore"));

    private final List<String> defaultBlocks;

    ProspectorTier(List<String> defaultBlocks) {
        this.defaultBlocks = defaultBlocks;
    }

    /**
     * {@code prospector_lowest} ... {@code prospector_best}: the item registry path and the config section name.
     */
    public String getName() {
        return "prospector_" + name().toLowerCase(Locale.ROOT);
    }

    public List<String> getDefaultBlocks() {
        return defaultBlocks;
    }
}
