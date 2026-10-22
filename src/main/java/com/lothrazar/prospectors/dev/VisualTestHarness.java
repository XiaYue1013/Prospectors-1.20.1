package com.lothrazar.prospectors.dev;

import com.lothrazar.prospectors.ProspectorTier;
import com.lothrazar.prospectors.Prospectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Development only (excluded from the release jar): with {@code -Dprospectors.visualTest=true} the client creates a
 * superflat world, builds a line of ores, right clicks with the prospectors through the normal client code
 * ({@code MultiPlayerGameMode#useItemOn}, which sends the click to the server), logs every chat message received
 * and takes screenshots into run/screenshots, then quits.
 */
@Mod.EventBusSubscriber(modid = Prospectors.MODID, value = Dist.CLIENT)
public final class VisualTestHarness {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("prospectors.visualTest");
    private static final String WORLD = "prospectors_visual_test";
    private static boolean worldRequested = false;
    private static int ticks = -1;
    private static int chatMessages = 0;
    private static BlockPos base;

    private VisualTestHarness() {}

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Init.Post event) {
        if (!ENABLED || worldRequested || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        worldRequested = true;
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        try {
            Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Visual test: could not delete old world", e);
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(42L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (ENABLED && ticks >= 0) {
            chatMessages++;
            LOGGER.info("Visual test: chat #{} at tick {}: '{}' (system={})", chatMessages, ticks, event.getMessage().getString(), event.isSystem());
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) {
            return;
        }
        ticks++;
        if (mc.screen instanceof PauseScreen) {
            mc.setScreen(null);
        }
        mc.getToasts().clear();
        switch (ticks) {
            case 60 -> server.execute(() -> build(server));
            // Stand 3 blocks north of the stone block, look at its north face
            case 100 -> look(server, new Vec3(0.5, 1.62, -1.5), new Vec3(0.5, 1.5, 2.0));
            case 130 -> logState(mc, "before use");
            // Ore Prospector (med): coal x2 and iron, not the diamond, not the gold behind the player
            case 140 -> use(mc, ProspectorTier.MED, base.offset(0, 1, 2), Direction.NORTH);
            // A second click inside the 10 tick cooldown must do nothing
            case 143 -> use(mc, ProspectorTier.MED, base.offset(0, 1, 2), Direction.NORTH);
            case 150 -> {
                logState(mc, "after the med prospector");
                shot(mc, "pr_01_med_found");
            }
            case 160 -> use(mc, ProspectorTier.BEST, base.offset(0, 1, 2), Direction.NORTH);
            case 170 -> shot(mc, "pr_02_best_found");
            case 180 -> use(mc, ProspectorTier.LOWEST, base.offset(0, 1, 2), Direction.NORTH);
            // The top face: the line goes down into the ground, which has nothing of interest
            case 200 -> use(mc, ProspectorTier.HIGH, base.offset(0, 1, 2), Direction.UP);
            case 210 -> {
                logState(mc, "after four tiers");
                shot(mc, "pr_03_lowest_and_none");
            }
            // Side view of the scene
            case 220 -> {
                mc.gui.getChat().clearMessages(false);
                look(server, new Vec3(4.5, 3.0, 2.5), new Vec3(0.5, 1.0, 5.0));
            }
            case 260 -> shot(mc, "pr_04_scene");
            // Creative inventory, prospectors tab, mouse over the ultimate prospector
            case 270 -> server.execute(() -> server.getPlayerList().getPlayers().get(0).setGameMode(GameType.CREATIVE));
            case 290 -> openCreativeTab(mc);
            case 300 -> hoverTabItem(mc, 4);
            case 310 -> shot(mc, "pr_05_creative_tab");
            case 315 -> mc.setScreen(null);
            // Traditional Chinese
            case 320 -> {
                mc.getLanguageManager().setSelected("zh_tw");
                mc.options.languageCode = "zh_tw";
                mc.reloadResourcePacks();
            }
            case 400 -> {
                server.execute(() -> server.getPlayerList().getPlayers().get(0).setGameMode(GameType.SURVIVAL));
                look(server, new Vec3(0.5, 1.62, -1.5), new Vec3(0.5, 1.5, 2.0));
            }
            case 420 -> use(mc, ProspectorTier.LOW, base.offset(0, 1, 2), Direction.NORTH);
            case 430 -> use(mc, ProspectorTier.HIGH, base.offset(0, 1, 2), Direction.UP);
            case 440 -> shot(mc, "pr_06_zh_tw");
            case 445 -> {
                mc.getLanguageManager().setSelected("en_us");
                mc.options.languageCode = "en_us";
                mc.reloadResourcePacks();
            }
            case 520 -> {
                LOGGER.info("Visual test: done, {} chat messages, stopping the client", chatMessages);
                mc.stop();
            }
            default -> {}
        }
    }

    private static void use(Minecraft mc, ProspectorTier tier, BlockPos pos, Direction face) {
        mc.player.getInventory().selected = tier.ordinal();
        Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        LOGGER.info("Visual test: tick {} {} on the {} face of {} -> {} (cooldown before: {})", ticks, tier.getName(), face, pos, result,
                mc.player.getCooldowns().getCooldownPercent(Prospectors.get(tier), 0));
    }

    private static void logState(Minecraft mc, String when) {
        StringBuilder text = new StringBuilder();
        for (ProspectorTier tier : ProspectorTier.values()) {
            ItemStack stack = mc.player.getInventory().getItem(tier.ordinal());
            text.append(tier.getName()).append(" damage ").append(stack.getDamageValue()).append('/').append(stack.getMaxDamage())
                    .append(" cooldown ").append(mc.player.getCooldowns().isOnCooldown(stack.getItem())).append("; ");
        }
        LOGGER.info("Visual test: client stacks {}: {}", when, text);
    }

    private static void openCreativeTab(Minecraft mc) {
        mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), true));
        try {
            Method select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
            select.setAccessible(true);
            select.invoke(mc.screen, Prospectors.TAB.get());
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Visual test: could not select the tab", e);
        }
        var items = Prospectors.TAB.get().getDisplayItems().stream().map(s -> s.getItem().toString()).toList();
        LOGGER.info("Visual test: creative tab '{}' shows {}", Prospectors.TAB.get().getDisplayName().getString(), items);
    }

    /**
     * Puts the mouse over a creative tab slot so the screenshot shows its tooltip (dev tool: sets the private position).
     */
    private static void hoverTabItem(Minecraft mc, int index) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen screen)) {
            return;
        }
        Slot slot = screen.getMenu().slots.get(index);
        double scale = mc.getWindow().getGuiScale();
        try {
            Field xpos = MouseHandler.class.getDeclaredField("xpos");
            Field ypos = MouseHandler.class.getDeclaredField("ypos");
            xpos.setAccessible(true);
            ypos.setAccessible(true);
            xpos.setDouble(mc.mouseHandler, (screen.getGuiLeft() + slot.x + 8) * scale);
            ypos.setDouble(mc.mouseHandler, (screen.getGuiTop() + slot.y + 8) * scale);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Visual test: could not move the mouse", e);
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> LOGGER.info("Visual test: {}", msg.getString()));
    }

    /**
     * Puts the player's eyes at {@code eye} looking at {@code target} (both relative to the scene base).
     */
    private static void look(MinecraftServer server, Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            Vec3 pos = Vec3.atLowerCornerOf(base).add(eye).subtract(0, 1.62, 0);
            player.teleportTo(player.serverLevel(), pos.x, pos.y, pos.z, yaw, pitch);
        });
    }

    private static void build(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        base = player.blockPosition();
        level.setDayTime(6000);
        // The clicked stone block at z+2, the ores behind it (south) on the search line, glass so they can be seen
        Block[] line = {Blocks.STONE, Blocks.COAL_ORE, Blocks.GLASS, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.GLASS, Blocks.DIAMOND_ORE, Blocks.COAL_ORE};
        for (int i = 0; i < line.length; i++) {
            level.setBlockAndUpdate(base.offset(0, 1, 2 + i), line[i].defaultBlockState());
        }
        // Gold behind the player (north of the clicked face): never found
        level.setBlockAndUpdate(base.offset(0, 1, -3), Blocks.GOLD_ORE.defaultBlockState());
        // Emerald next to the line: not on it, never found
        level.setBlockAndUpdate(base.offset(1, 1, 5), Blocks.EMERALD_ORE.defaultBlockState());

        player.getInventory().clearContent();
        for (ProspectorTier tier : ProspectorTier.values()) {
            player.getInventory().setItem(tier.ordinal(), new ItemStack(Prospectors.get(tier)));
        }
        player.getInventory().selected = 0;
        LOGGER.info("Visual test: scene built at {}", base);
    }
}
