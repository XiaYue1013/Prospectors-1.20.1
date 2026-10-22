package com.lothrazar.prospectors.gametest;

import com.google.gson.JsonObject;
import com.lothrazar.prospectors.BlockMatcher;
import com.lothrazar.prospectors.ItemProspector;
import com.lothrazar.prospectors.ProspectorConfig;
import com.lothrazar.prospectors.ProspectorSearch;
import com.lothrazar.prospectors.ProspectorTier;
import com.lothrazar.prospectors.Prospectors;
import com.lothrazar.prospectors.dev.TestOreMod;
import com.lothrazar.prospectors.network.SyncDurabilityPacket;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-game verification of the prospectors, run with {@code gradlew runGameTestServer}. The arena is an empty 11x11x11
 * structure; the tests place the blocks they need. Everything a test does happens within one tick, so tests that
 * share the config or the server never see each other's state.
 */
@GameTestHolder(Prospectors.MODID)
@PrefixGameTestTemplate(false)
public class ProspectorsGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ARENA = "arena";
    private static final BlockPos CENTER = new BlockPos(5, 5, 5);

    // ---------------------------------------------------------------- helpers

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    private static BlockMatcher matcher(String... entries) {
        return BlockMatcher.parse("gametest", List.of(entries), LOGGER);
    }

    private static String describe(List<ProspectorSearch.Found> found) {
        StringBuilder text = new StringBuilder("[");
        for (ProspectorSearch.Found entry : found) {
            text.append(entry.getName().getString()).append(' ').append(entry.getCount()).append("; ");
        }
        return text.append(']').toString();
    }

    private static List<ProspectorSearch.Found> search(GameTestHelper helper, BlockPos relative, Direction side, int range, BlockMatcher matcher, boolean blacklist) {
        return new ArrayList<>(ProspectorSearch.search(helper.getLevel(), helper.absolutePos(relative), side, range, matcher, blacklist));
    }

    /**
     * The search result must be exactly these (item, count) pairs, in this order.
     */
    private static void expect(List<ProspectorSearch.Found> found, String what, Object... itemsAndCounts) {
        check(found.size() * 2 == itemsAndCounts.length, what + ": expected " + Arrays.toString(itemsAndCounts) + " but found " + describe(found));
        for (int i = 0; i < found.size(); i++) {
            Item item = (Item) itemsAndCounts[i * 2];
            int count = (Integer) itemsAndCounts[i * 2 + 1];
            ProspectorSearch.Found entry = found.get(i);
            check(entry.getName().equals(new ItemStack(item).getHoverName()) && entry.getCount() == count,
                    what + ": expected " + Arrays.toString(itemsAndCounts) + " but found " + describe(found));
        }
    }

    /**
     * A server player that records the chat messages it is sent.
     */
    private static final class TestPlayer extends FakePlayer {

        final List<Component> messages = new ArrayList<>();

        TestPlayer(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            messages.add(message);
        }
    }

    private static TestPlayer player(GameTestHelper helper, String name, ProspectorTier tier, GameType mode) {
        TestPlayer player = new TestPlayer(helper.getLevel(), name);
        player.setGameMode(mode);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Prospectors.get(tier)));
        return player;
    }

    /**
     * A right click on {@code relative}'s {@code side} face, through the same server code as a real client's click
     * (block interaction first, then the cooldown check, then {@code Item#useOn}).
     */
    private static InteractionResult rightClick(GameTestHelper helper, Player player, BlockPos relative, Direction side) {
        BlockPos pos = helper.absolutePos(relative);
        Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getNormal()).scale(0.5));
        return ((TestPlayer) player).gameMode.useItemOn((TestPlayer) player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, side, pos, false));
    }

    private static String key(Component message) {
        return message.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    private static String literal(Component component) {
        return component.getContents() instanceof LiteralContents text ? text.text() : "";
    }

    // ---------------------------------------------------------------- the directional search, all six faces

    /**
     * Click the {@code side} face of the center block: three iron ores lie in the opposite direction (into the
     * block), two gold ores on the clicked side (towards the player) and a diamond ore next to the line. Only the
     * iron may be found.
     */
    private static void checkDirection(GameTestHelper helper, Direction side) {
        Direction into = side.getOpposite();
        helper.setBlock(CENTER, Blocks.STONE);
        for (int i = 1; i <= 3; i++) {
            helper.setBlock(CENTER.relative(into, i), Blocks.IRON_ORE);
        }
        helper.setBlock(CENTER.relative(side, 1), Blocks.GOLD_ORE);
        helper.setBlock(CENTER.relative(side, 2), Blocks.GOLD_ORE);
        Direction across = into.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
        helper.setBlock(CENTER.relative(into, 2).relative(across), Blocks.DIAMOND_ORE);

        List<ProspectorSearch.Found> found = search(helper, CENTER, side, 5, matcher("minecraft:iron_ore", "minecraft:gold_ore", "minecraft:diamond_ore"), false);
        expect(found, "click on the " + side + " face searches " + into, Items.IRON_ORE, 3);

        // A real right click with the default config (range 32) reports what a search from the clicked block and face
        // finds. Horizontal range-32 lines leave the arena and may cross the arenas of other tests, so the expected
        // messages come from the same search instead of being fixed.
        ProspectorTier tier = ProspectorTier.MED;
        ProspectorConfig.Tier settings = ProspectorConfig.get(tier);
        List<ProspectorSearch.Found> expected = search(helper, CENTER, side, settings.range.get(), settings.matcher(), false);
        check(!expected.isEmpty() && expected.get(0).getName().equals(Items.IRON_ORE.getDescription()) && expected.get(0).getCount() >= 3,
                side + " face with range 32: the iron ores must be found first, got " + describe(expected));
        TestPlayer player = player(helper, "dir_" + side, tier, GameType.SURVIVAL);
        check(rightClick(helper, player, CENTER, side).consumesAction(), "right click on the " + side + " face was not used");
        List<Component> messages = expected.stream()
                .map(f -> (Component) Component.translatable("prospector.found").append(f.getName()).append(" " + f.getCount())).toList();
        check(player.messages.equals(messages), side + " face: expected " + messages + " got " + player.messages);
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void directionUp(GameTestHelper helper) {
        checkDirection(helper, Direction.UP);
    }

    @GameTest(template = ARENA)
    public static void directionDown(GameTestHelper helper) {
        checkDirection(helper, Direction.DOWN);
    }

    @GameTest(template = ARENA)
    public static void directionNorth(GameTestHelper helper) {
        checkDirection(helper, Direction.NORTH);
    }

    @GameTest(template = ARENA)
    public static void directionSouth(GameTestHelper helper) {
        checkDirection(helper, Direction.SOUTH);
    }

    @GameTest(template = ARENA)
    public static void directionEast(GameTestHelper helper) {
        checkDirection(helper, Direction.EAST);
    }

    @GameTest(template = ARENA)
    public static void directionWest(GameTestHelper helper) {
        checkDirection(helper, Direction.WEST);
    }

    /**
     * 1.12 loop {@code for (i = 0; i < range; i++) pos.offset(direction, i)}: the clicked block is the first block
     * searched and the last one is {@code range - 1} blocks away.
     */
    @GameTest(template = ARENA)
    public static void rangeStartsAtClickedBlock(GameTestHelper helper) {
        BlockPos top = new BlockPos(5, 9, 5);
        helper.setBlock(top, Blocks.COAL_ORE);                // i = 0, the clicked block
        helper.setBlock(top.below(2), Blocks.STONE);
        helper.setBlock(top.below(4), Blocks.COAL_ORE);       // i = 4, last block of range 5
        helper.setBlock(top.below(5), Blocks.COAL_ORE);       // i = 5, out of range 5
        BlockMatcher coal = matcher("minecraft:coal_ore");
        expect(search(helper, top, Direction.UP, 5, coal, false), "range 5", Items.COAL_ORE, 2);
        expect(search(helper, top, Direction.UP, 6, coal, false), "range 6", Items.COAL_ORE, 3);
        expect(search(helper, top, Direction.UP, 1, coal, false), "range 1", Items.COAL_ORE, 1);
        helper.succeed();
    }

    // ---------------------------------------------------------------- whitelist / blacklist / matching

    @GameTest(template = ARENA)
    public static void blacklistReportsEverythingElse(GameTestHelper helper) {
        // Clicked on the west face: the line runs east
        helper.setBlock(CENTER, Blocks.STONE);
        helper.setBlock(CENTER.east(1), Blocks.DIRT);
        // east(2) stays air
        helper.setBlock(CENTER.east(3), Blocks.DIAMOND_ORE);
        helper.setBlock(CENTER.east(4), Blocks.STONE);
        expect(search(helper, CENTER, Direction.WEST, 5, matcher("minecraft:stone"), true), "blacklist [stone]",
                Items.DIRT, 1, Items.DIAMOND_ORE, 1);
        expect(search(helper, CENTER, Direction.WEST, 5, matcher("minecraft:stone"), false), "whitelist [stone]",
                Items.STONE, 2);
        expect(search(helper, CENTER, Direction.WEST, 5, matcher(), true), "empty blacklist (everything but air)",
                Items.STONE, 2, Items.DIRT, 1, Items.DIAMOND_ORE, 1);
        expect(search(helper, CENTER, Direction.WEST, 5, matcher(), false), "empty whitelist");
        helper.succeed();
    }

    /**
     * 1.12 listed {@code redstone_ore/0} and {@code /1}; 1.20 has one block with a {@code lit} property. Both states
     * are one block (and one "Redstone Ore" line), a state entry selects one of them.
     */
    @GameTest(template = ARENA)
    public static void redstoneOreStates(GameTestHelper helper) {
        BlockState unlit = Blocks.REDSTONE_ORE.defaultBlockState();
        BlockState lit = unlit.setValue(RedStoneOreBlock.LIT, true);
        helper.setBlock(CENTER, unlit);
        helper.setBlock(CENTER.below(1), lit);
        helper.setBlock(CENTER.below(2), Blocks.DEEPSLATE_REDSTONE_ORE);
        helper.setBlock(CENTER.below(3), lit);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("minecraft:redstone_ore"), false), "redstone_ore", Items.REDSTONE_ORE, 3);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("minecraft:redstone_ore[lit=true]"), false), "redstone_ore[lit=true]", Items.REDSTONE_ORE, 2);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("minecraft:redstone_ore[lit=false]", "minecraft:deepslate_redstone_ore"), false),
                "redstone_ore[lit=false] + deepslate", Items.REDSTONE_ORE, 1, Items.DEEPSLATE_REDSTONE_ORE, 1);
        // The 1.12 entries still load (as the whole block)
        expect(search(helper, CENTER, Direction.UP, 5, matcher("minecraft:redstone_ore/0", "minecraft:redstone_ore/1"), false),
                "1.12 redstone_ore/0 + /1", Items.REDSTONE_ORE, 3);
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void blockTagsAndModdedOres(GameTestHelper helper) {
        Block mythril = TestOreMod.MYTHRIL_ORE.get();
        helper.setBlock(CENTER, Blocks.IRON_ORE);
        helper.setBlock(CENTER.below(1), Blocks.DEEPSLATE_IRON_ORE);
        helper.setBlock(CENTER.below(2), mythril);
        helper.setBlock(CENTER.below(3), Blocks.GOLD_ORE);
        helper.setBlock(CENTER.below(4), mythril);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("#forge:ores/iron"), false), "#forge:ores/iron",
                Items.IRON_ORE, 1, Items.DEEPSLATE_IRON_ORE, 1);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("#forge:ores/mythril"), false), "#forge:ores/mythril (other mod's tag)",
                TestOreMod.MYTHRIL_ORE_ITEM.get(), 2);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("prospectorstest:mythril_ore", "#minecraft:gold_ores"), false), "other mod's block ID + vanilla tag",
                TestOreMod.MYTHRIL_ORE_ITEM.get(), 2, Items.GOLD_ORE, 1);
        expect(search(helper, CENTER, Direction.UP, 5, matcher("#forge:ores"), true), "blacklist #forge:ores",
                TestOreMod.MYTHRIL_ORE_ITEM.get(), 2);
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void invalidEntriesAreSkipped(GameTestHelper helper) {
        BlockMatcher matcher = matcher("notamod:nothing", "Bad ID!!", "#bad tag", "#notamod:nothing", "minecraft:redstone_ore[lit=maybe]",
                "minecraft:redstone_ore[nosuch=true]", "", "   ", "minecraft:diamond_ore", "minecraft:diamond_ore", " minecraft:diamond_ore ",
                "minecraft:quartz_ore", "othermod:custom_ore/3");
        check(matcher.matches(Blocks.DIAMOND_ORE.defaultBlockState()), "valid entry next to invalid ones must still work");
        check(matcher.matches(Blocks.NETHER_QUARTZ_ORE.defaultBlockState()), "1.12 minecraft:quartz_ore must map to nether_quartz_ore");
        for (Block block : List.of(Blocks.REDSTONE_ORE, Blocks.STONE, Blocks.AIR, Blocks.DEEPSLATE_DIAMOND_ORE)) {
            check(!matcher.matches(block.defaultBlockState()), block + " must not match");
        }
        check(matcher("minecraft:lit_redstone_ore").matches(Blocks.REDSTONE_ORE.defaultBlockState().setValue(RedStoneOreBlock.LIT, true))
                        && !matcher("minecraft:lit_redstone_ore").matches(Blocks.REDSTONE_ORE.defaultBlockState()),
                "1.12 minecraft:lit_redstone_ore must map to redstone_ore[lit=true]");
        check(matcher("#notamod:nothing").isEmpty() == false && !matcher("#notamod:nothing").matches(Blocks.STONE.defaultBlockState()),
                "an unknown tag matches nothing");
        check(matcher("notamod:nothing", "Bad ID!!").isEmpty(), "only invalid entries: empty matcher");
        helper.succeed();
    }

    /**
     * The defaults of each tier: the 1.12 lists with 1.20 IDs, deepslate variants, copper from the iron tier and
     * nether gold from the gold tier; lower tiers must not find higher ores.
     */
    @GameTest(template = ARENA)
    public static void defaultTierLists(GameTestHelper helper) {
        Map<ProspectorTier, List<Block>> finds = Map.of(
                ProspectorTier.LOWEST, List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND),
                ProspectorTier.LOW, List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
                ProspectorTier.MED, List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COAL_ORE,
                        Blocks.DEEPSLATE_COAL_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
                ProspectorTier.HIGH, List.of(Blocks.NETHER_QUARTZ_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
                        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
                        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
                ProspectorTier.BEST, List.of(Blocks.NETHER_QUARTZ_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
                        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
                        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
        List<Block> all = List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
                Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE,
                Blocks.DEEPSLATE_EMERALD_ORE, Blocks.ANCIENT_DEBRIS, Blocks.STONE, Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.SUSPICIOUS_SAND, Blocks.RAW_IRON_BLOCK,
                TestOreMod.MYTHRIL_ORE.get());
        for (ProspectorTier tier : ProspectorTier.values()) {
            ProspectorConfig.Tier settings = ProspectorConfig.get(tier);
            check(settings.blockList.get().equals(tier.getDefaultBlocks()), tier.getName() + ": the test needs the default config, found " + settings.blockList.get());
            check(!settings.isBlacklist.get() && settings.range.get() == 32 && settings.cooldown.get() == 10 && settings.durability.get() == 200,
                    tier.getName() + ": expected the 1.12 defaults whitelist/32/10/200");
            BlockMatcher matcher = settings.matcher();
            for (Block block : all) {
                boolean expected = finds.get(tier).contains(block);
                check(matcher.matches(block.defaultBlockState()) == expected, tier.getName() + (expected ? " must find " : " must not find ") + block);
            }
        }
        check(ProspectorConfig.get(ProspectorTier.HIGH).matcher().matches(Blocks.REDSTONE_ORE.defaultBlockState().setValue(RedStoneOreBlock.LIT, true)),
                "the high tier must find lit redstone ore");
        helper.succeed();
    }

    // ---------------------------------------------------------------- use: messages, durability, cooldown

    @GameTest(template = ARENA)
    public static void messagesFoundAndNone(GameTestHelper helper) {
        helper.setBlock(CENTER, Blocks.STONE);
        helper.setBlock(CENTER.below(1), Blocks.COAL_ORE);
        helper.setBlock(CENTER.below(2), Blocks.DEEPSLATE_COAL_ORE);
        helper.setBlock(CENTER.below(3), Blocks.COAL_ORE);
        helper.setBlock(CENTER.below(4), Blocks.GRAVEL);
        TestPlayer player = player(helper, "messages", ProspectorTier.LOWEST, GameType.SURVIVAL);
        rightClick(helper, player, CENTER, Direction.UP);
        List<String> text = player.messages.stream().map(Component::getString).toList();
        check(player.messages.size() == 3, "expected 3 messages (coal, deepslate coal, gravel), got " + text);
        Component first = player.messages.get(0);
        check(key(first).equals("prospector.found") && first.getSiblings().size() == 2
                        && first.getSiblings().get(0).equals(Items.COAL_ORE.getDescription()) && literal(first.getSiblings().get(1)).equals(" 2"),
                "first message must be 'prospector.found' + Coal Ore + ' 2', got " + first);
        check(player.messages.get(1).getSiblings().get(0).equals(Items.DEEPSLATE_COAL_ORE.getDescription()) && literal(player.messages.get(1).getSiblings().get(1)).equals(" 1"),
                "second message must be deepslate coal ore 1, got " + text);
        check(player.messages.get(2).getSiblings().get(0).equals(Items.GRAVEL.getDescription()), "third message must be gravel, got " + text);

        // Nothing in reach: one "none" message with the range
        TestPlayer other = player(helper, "messages_none", ProspectorTier.BEST, GameType.SURVIVAL);
        helper.setBlock(CENTER.east(2), Blocks.STONE);
        rightClick(helper, other, CENTER.east(2), Direction.UP);
        check(other.messages.size() == 1 && key(other.messages.get(0)).equals("prospector.none") && literal(other.messages.get(0).getSiblings().get(0)).equals("32"),
                "expected one 'prospector.none' + '32' message, got " + other.messages);
        check(other.getMainHandItem().getDamageValue() == 1, "a search that finds nothing still costs durability");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void durabilityAndCooldown(GameTestHelper helper) {
        helper.setBlock(CENTER, Blocks.STONE);
        helper.setBlock(CENTER.below(1), Blocks.COAL_ORE);
        TestPlayer player = player(helper, "cooldown", ProspectorTier.LOW, GameType.SURVIVAL);
        ItemStack stack = player.getMainHandItem();
        Item item = stack.getItem();
        check(stack.getMaxDamage() == 200 && stack.getMaxStackSize() == 1, "durability must be 200 from the config and the stack size 1");

        check(rightClick(helper, player, CENTER, Direction.UP).consumesAction(), "first use must work");
        check(stack.getDamageValue() == 1 && player.messages.size() == 1, "first use: 1 damage and 1 message, got " + stack.getDamageValue() + "/" + player.messages.size());
        check(player.getCooldowns().isOnCooldown(item), "the cooldown must start");

        // Clicking again during the cooldown does nothing
        InteractionResult again = rightClick(helper, player, CENTER, Direction.UP);
        check(!again.consumesAction() && stack.getDamageValue() == 1 && player.messages.size() == 1, "a click during the cooldown must do nothing, got " + again);

        // 10 ticks (FakePlayer does not tick, so tick its cooldowns by hand)
        for (int i = 0; i < 9; i++) {
            player.getCooldowns().tick();
        }
        check(player.getCooldowns().isOnCooldown(item), "still cooling down after 9 ticks");
        player.getCooldowns().tick();
        check(!player.getCooldowns().isOnCooldown(item), "cooldown over after 10 ticks");
        rightClick(helper, player, CENTER, Direction.UP);
        check(stack.getDamageValue() == 2 && player.messages.size() == 2, "use after the cooldown must work again");

        // Last use breaks the tool
        stack.setDamageValue(stack.getMaxDamage() - 1);
        for (int i = 0; i < 10; i++) {
            player.getCooldowns().tick();
        }
        rightClick(helper, player, CENTER, Direction.UP);
        check(player.getMainHandItem().isEmpty(), "the tool must break on its last use");

        // Creative: no durability used, the cooldown still applies (as in 1.12)
        TestPlayer creative = player(helper, "creative", ProspectorTier.LOW, GameType.CREATIVE);
        rightClick(helper, creative, CENTER, Direction.UP);
        check(creative.getMainHandItem().getDamageValue() == 0 && creative.messages.size() == 1, "creative: no damage, one message");
        check(creative.getCooldowns().isOnCooldown(item), "creative: the cooldown still starts");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void twoPlayersAtOnce(GameTestHelper helper) {
        helper.setBlock(CENTER, Blocks.STONE);
        helper.setBlock(CENTER.below(1), Blocks.COAL_ORE);
        BlockPos other = CENTER.east(3);
        helper.setBlock(other, Blocks.STONE);
        helper.setBlock(other.below(1), Blocks.IRON_ORE);
        helper.setBlock(other.below(2), Blocks.IRON_ORE);
        TestPlayer alice = player(helper, "alice", ProspectorTier.MED, GameType.SURVIVAL);
        TestPlayer bob = player(helper, "bob", ProspectorTier.MED, GameType.SURVIVAL);
        rightClick(helper, alice, CENTER, Direction.UP);
        rightClick(helper, bob, other, Direction.UP);
        check(alice.messages.size() == 1 && alice.messages.get(0).getSiblings().get(0).equals(Items.COAL_ORE.getDescription()),
                "alice must only see her coal, got " + alice.messages.stream().map(Component::getString).toList());
        check(bob.messages.size() == 1 && bob.messages.get(0).getSiblings().get(0).equals(Items.IRON_ORE.getDescription())
                        && literal(bob.messages.get(0).getSiblings().get(1)).equals(" 2"),
                "bob must only see his 2 iron, got " + bob.messages.stream().map(Component::getString).toList());
        check(alice.getCooldowns().isOnCooldown(Prospectors.get(ProspectorTier.MED)) && bob.getCooldowns().isOnCooldown(Prospectors.get(ProspectorTier.MED)),
                "each player has their own cooldown, bob was not blocked by alice's");
        check(alice.getMainHandItem().getDamageValue() == 1 && bob.getMainHandItem().getDamageValue() == 1, "each tool took exactly one damage");
        helper.succeed();
    }

    // ---------------------------------------------------------------- world safety

    /**
     * A range-256 line that leaves the loaded area must not load (or generate) the chunks it crosses.
     */
    @GameTest(template = ARENA)
    public static void unloadedChunksStayUnloaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(CENTER);
        Direction into = start.getX() >= level.getSharedSpawnPos().getX() ? Direction.EAST : Direction.WEST;
        List<Long> unloaded = new ArrayList<>();
        for (int i = 0; i < 256; i += 16) {
            BlockPos pos = start.relative(into, i);
            int x = SectionPos.blockToSectionCoord(pos.getX());
            int z = SectionPos.blockToSectionCoord(pos.getZ());
            if (level.getChunkSource().getChunkNow(x, z) == null) {
                unloaded.add((long) x << 32 | (z & 0xFFFFFFFFL));
            }
        }
        check(!unloaded.isEmpty(), "test setup: every chunk on the line is loaded, nothing to check");
        ProspectorSearch.search(level, start, into.getOpposite(), 256, matcher(), true);
        for (long chunk : unloaded) {
            int x = (int) (chunk >> 32);
            int z = (int) chunk;
            check(level.getChunkSource().getChunkNow(x, z) == null && !level.hasChunk(x, z), "chunk " + x + "," + z + " was loaded by the search");
        }
        LOGGER.info("unloadedChunksStayUnloaded: {} unloaded chunks on the line stayed unloaded", unloaded.size());
        helper.succeed();
    }

    /**
     * Lines that reach the top or the bottom of the world stop there; ores on the last layer are still found.
     */
    @GameTest(template = ARENA)
    public static void buildHeightLimits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos column = helper.absolutePos(CENTER);
        BlockPos top = new BlockPos(column.getX(), level.getMaxBuildHeight() - 1, column.getZ());
        BlockPos bottom = new BlockPos(column.getX(), level.getMinBuildHeight(), column.getZ());
        BlockState oldTop = level.getBlockState(top);
        BlockState oldBottom = level.getBlockState(bottom);
        try {
            level.setBlock(top, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(bottom, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockMatcher coal = matcher("minecraft:coal_ore");
            List<ProspectorSearch.Found> up = new ArrayList<>(ProspectorSearch.search(level, top.below(2), Direction.DOWN, 256, coal, false));
            expect(up, "upwards to the build limit", Items.COAL_ORE, 1);
            List<ProspectorSearch.Found> down = new ArrayList<>(ProspectorSearch.search(level, bottom.above(2), Direction.UP, 256, coal, false));
            expect(down, "downwards to the bottom of the world", Items.COAL_ORE, 1);
        } finally {
            level.setBlock(top, oldTop, Block.UPDATE_CLIENTS);
            level.setBlock(bottom, oldBottom, Block.UPDATE_CLIENTS);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- recipes, durability sync, repair, lang

    private static final class NoMenu extends AbstractContainerMenu {

        NoMenu() {
            super(null, 0);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    /**
     * The 1.12 shaped pattern {@code " sg", " bs", "b  "}.
     */
    private static Optional<CraftingRecipe> craft(GameTestHelper helper, Item b, Item s, Item g) {
        TransientCraftingContainer grid = new TransientCraftingContainer(new NoMenu(), 3, 3);
        Item[] layout = {null, s, g, null, b, s, b, null, null};
        for (int i = 0; i < 9; i++) {
            grid.setItem(i, layout[i] == null ? ItemStack.EMPTY : new ItemStack(layout[i]));
        }
        return helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, helper.getLevel());
    }

    private static void checkCraft(GameTestHelper helper, ProspectorTier tier, Item b, Item s, Item g) {
        Optional<CraftingRecipe> recipe = craft(helper, b, s, g);
        check(recipe.isPresent() && recipe.get().getId().equals(ResourceLocation.fromNamespaceAndPath(Prospectors.MODID, tier.getName()))
                        && recipe.get().getResultItem(helper.getLevel().registryAccess()).is(Prospectors.get(tier)),
                tier.getName() + " must be crafted from b=" + b + " s=" + s + " g=" + g + ", got " + recipe.map(CraftingRecipe::getId));
    }

    @GameTest(template = ARENA)
    public static void recipes(GameTestHelper helper) {
        checkCraft(helper, ProspectorTier.LOWEST, Items.COBBLESTONE, Items.STICK, Items.OAK_LOG);
        checkCraft(helper, ProspectorTier.LOWEST, Items.COBBLESTONE, Items.STICK, Items.SPRUCE_LOG);
        checkCraft(helper, ProspectorTier.LOW, Items.BIRCH_LOG, Items.COAL, Items.GLASS);
        // 1.12 "Items.COAL" matched every metadata, charcoal included
        checkCraft(helper, ProspectorTier.LOW, Items.OAK_LOG, Items.CHARCOAL, Items.GLASS);
        checkCraft(helper, ProspectorTier.MED, Items.LAPIS_LAZULI, Items.REDSTONE, Items.GLASS);
        checkCraft(helper, ProspectorTier.HIGH, Items.EMERALD, Items.REDSTONE, Items.GLASS);
        checkCraft(helper, ProspectorTier.BEST, Items.BLAZE_ROD, Items.DIAMOND, Items.GLASS);
        check(craft(helper, Items.EMERALD, Items.REDSTONE, Items.WHITE_STAINED_GLASS).isEmpty(), "stained glass is not colorless glass");
        check(craft(helper, Items.MOSSY_COBBLESTONE, Items.STICK, Items.OAK_LOG).isEmpty(), "ore dictionary 'cobblestone' was plain cobblestone");
        helper.succeed();
    }

    /**
     * As in 1.12 (Item#isRepairable defaulted to true for damageable items), two worn prospectors of the same tier
     * combine in the crafting grid.
     */
    @GameTest(template = ARENA)
    public static void repairInCraftingGrid(GameTestHelper helper) {
        ItemProspector item = Prospectors.get(ProspectorTier.HIGH);
        TransientCraftingContainer grid = new TransientCraftingContainer(new NoMenu(), 3, 3);
        ItemStack a = new ItemStack(item);
        a.setDamageValue(150);
        ItemStack b = new ItemStack(item);
        b.setDamageValue(160);
        grid.setItem(0, a);
        grid.setItem(4, b);
        Optional<CraftingRecipe> recipe = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, helper.getLevel());
        check(recipe.isPresent(), "two worn prospectors must combine");
        ItemStack result = recipe.get().assemble(grid, helper.getLevel().registryAccess());
        // vanilla: (200 - 150) + (200 - 160) + 200 * 5 / 100 = 100 uses left
        check(result.is(item) && result.getDamageValue() == 100, "expected 100 uses left, got damage " + result.getDamageValue());
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void durabilityFromServer(GameTestHelper helper) {
        ProspectorConfig.Tier settings = ProspectorConfig.get(ProspectorTier.BEST);
        ItemStack stack = new ItemStack(Prospectors.get(ProspectorTier.BEST));
        stack.setDamageValue(250);
        try {
            settings.setSyncedDurability(500);
            check(stack.getMaxDamage() == 500 && stack.getBarWidth() == Math.round(13.0F - 250 * 13.0F / 500),
                    "the durability received from the server must be used, got " + stack.getMaxDamage());
        } finally {
            settings.setSyncedDurability(0);
        }
        check(stack.getMaxDamage() == 200, "back to the local config");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncDurabilityPacket.fromConfig().encode(buf);
        int[] decoded = SyncDurabilityPacket.decode(buf).durability();
        check(Arrays.equals(decoded, new int[]{200, 200, 200, 200, 200}), "packet round trip, got " + Arrays.toString(decoded));
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void registryNamesAndLanguages(GameTestHelper helper) {
        List<String> keys = new ArrayList<>(List.of("itemGroup.prospectors", "prospector.none", "prospector.found"));
        for (ProspectorTier tier : ProspectorTier.values()) {
            ItemProspector item = Prospectors.get(tier);
            ResourceLocation id = Prospectors.PROSPECTORS.get(tier).getId();
            check(id.toString().equals("prospectors:" + tier.getName()), "registry name " + id);
            check(item.getType() == tier, "tier of " + id);
            keys.add(item.getDescriptionId());
        }
        check(keys.contains("item.prospectors.prospector_lowest") && keys.contains("item.prospectors.prospector_best"), "description ids " + keys);
        for (String lang : List.of("en_us", "ru_ru", "zh_tw")) {
            String path = "/assets/prospectors/lang/" + lang + ".json";
            try (InputStream in = Prospectors.class.getResourceAsStream(path)) {
                check(in != null, path + " missing");
                JsonObject json = GsonHelper.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String key : keys) {
                    check(json.has(key) && !json.get(key).getAsString().isEmpty(), lang + " has no '" + key + "'");
                }
            } catch (java.io.IOException e) {
                throw new GameTestAssertException(path + ": " + e);
            }
        }
        helper.succeed();
    }
}
