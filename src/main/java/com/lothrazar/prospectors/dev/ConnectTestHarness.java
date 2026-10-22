package com.lothrazar.prospectors.dev;

import com.lothrazar.prospectors.ProspectorTier;
import com.lothrazar.prospectors.Prospectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
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

/**
 * Development only (excluded from the release jar): with {@code -Dprospectors.connectTest=host:port} the client joins
 * that (real, separately started) server, waits until it holds a med and a lowest prospector (given by the server
 * console), right clicks the top face of the stone at 0,100,0 like a player, logs the chat and the durability the
 * client sees (the server's config, synced), and keeps logging the durability until the server's config changes it.
 */
@Mod.EventBusSubscriber(modid = Prospectors.MODID, value = Dist.CLIENT)
public final class ConnectTestHarness {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String ADDRESS = System.getProperty("prospectors.connectTest", "");
    private static final BlockPos CLICKED = new BlockPos(0, 100, 0);
    private static boolean connecting = false;
    private static int ticks = -1;
    private static int firstDurability = -1;

    private ConnectTestHarness() {}

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Init.Post event) {
        if (ADDRESS.isEmpty() || connecting || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        connecting = true;
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        LOGGER.info("Connect test: joining {}", ADDRESS);
        ConnectScreen.startConnecting(event.getScreen(), mc, ServerAddress.parseString(ADDRESS), new ServerData("connect test", ADDRESS, false), false);
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (!ADDRESS.isEmpty()) {
            LOGGER.info("Connect test: chat at tick {}: '{}'", ticks, event.getMessage().getString());
        }
    }

    private static int slotOf(Minecraft mc, ProspectorTier tier) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Prospectors.get(tier))) {
                return i;
            }
        }
        return -1;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (ADDRESS.isEmpty() || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (ticks < 0) {
            if (slotOf(mc, ProspectorTier.MED) < 0 || slotOf(mc, ProspectorTier.LOWEST) < 0) {
                return;
            }
            ticks = 0;
            LOGGER.info("Connect test: holding the prospectors, starting");
        }
        ticks++;
        switch (ticks) {
            case 20 -> {
                logDurability(mc, "joined");
                firstDurability = new ItemStack(Prospectors.get(ProspectorTier.MED)).getMaxDamage();
                use(mc, ProspectorTier.MED);
            }
            case 23 -> use(mc, ProspectorTier.MED);
            case 40 -> use(mc, ProspectorTier.LOWEST);
            case 60 -> logDurability(mc, "after use");
            default -> {
                if (ticks > 60 && ticks % 20 == 0) {
                    int now = new ItemStack(Prospectors.get(ProspectorTier.MED)).getMaxDamage();
                    if (now != firstDurability || ticks >= 1200) {
                        logDurability(mc, now != firstDurability ? "server config changed" : "timeout, no change seen");
                        LOGGER.info("Connect test: done, disconnecting and stopping");
                        mc.level.disconnect();
                        mc.clearLevel();
                        logDurability(mc, "after disconnect (local config)");
                        mc.stop();
                    }
                }
            }
        }
    }

    private static void use(Minecraft mc, ProspectorTier tier) {
        mc.player.getInventory().selected = slotOf(mc, tier);
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(CLICKED).add(0, 0.5, 0), Direction.UP, CLICKED, false));
        LOGGER.info("Connect test: tick {} {} on the top face of {} -> {}", ticks, tier.getName(), CLICKED, result);
    }

    private static void logDurability(Minecraft mc, String when) {
        StringBuilder text = new StringBuilder();
        for (ProspectorTier tier : ProspectorTier.values()) {
            text.append(tier.getName()).append(" max ").append(new ItemStack(Prospectors.get(tier)).getMaxDamage());
            int slot = mc.player == null ? -1 : slotOf(mc, tier);
            if (slot >= 0) {
                ItemStack held = mc.player.getInventory().getItem(slot);
                text.append(" (held: damage ").append(held.getDamageValue()).append(", bar ").append(held.getBarWidth()).append(')');
            }
            text.append("; ");
        }
        LOGGER.info("Connect test: durability {}: {}", when, text);
    }
}
