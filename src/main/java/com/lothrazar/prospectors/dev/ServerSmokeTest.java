package com.lothrazar.prospectors.dev;

import com.lothrazar.prospectors.ProspectorConfig;
import com.lothrazar.prospectors.ProspectorTier;
import com.lothrazar.prospectors.Prospectors;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Development only (excluded from the release jar): with {@code -Dprospectors.serverSmokeTest=true} a dedicated
 * server logs what the mod loaded, right clicks a line of ores with each prospector (a fake player through the
 * server's normal right click code), runs for 100 ticks and stops itself.
 */
@Mod.EventBusSubscriber(modid = Prospectors.MODID)
public final class ServerSmokeTest {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("prospectors.serverSmokeTest");
    private static int ticks = -1;

    private ServerSmokeTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!ENABLED) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (ProspectorTier tier : ProspectorTier.values()) {
            ResourceLocation id = Prospectors.PROSPECTORS.get(tier).getId();
            boolean recipe = server.getRecipeManager().byKey(id).isPresent();
            ProspectorConfig.Tier settings = ProspectorConfig.get(tier);
            LOGGER.info("Server smoke test: dedicated={} item={} recipe={} maxDamage={} range={} cooldown={} blacklist={} list={}",
                    server.isDedicatedServer(), id, recipe, new ItemStack(Prospectors.get(tier)).getMaxDamage(), settings.range.get(),
                    settings.cooldown.get(), settings.isBlacklist.get(), settings.blockList.get());
        }

        ServerLevel level = server.overworld();
        BlockPos clicked = new BlockPos(level.getSharedSpawnPos().getX(), 200, level.getSharedSpawnPos().getZ());
        level.setBlockAndUpdate(clicked, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(clicked.above(1), Blocks.COAL_ORE.defaultBlockState());
        level.setBlockAndUpdate(clicked.above(2), Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(clicked.above(3), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
        for (ProspectorTier tier : ProspectorTier.values()) {
            List<String> messages = new ArrayList<>();
            FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "smoke_" + tier.getName())) {
                @Override
                public void sendSystemMessage(Component message, boolean overlay) {
                    messages.add(message.getString());
                }
            };
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Prospectors.get(tier)));
            // Bottom face: the line goes up through the ores
            var result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(clicked).add(0, -0.5, 0), Direction.DOWN, clicked, false));
            LOGGER.info("Server smoke test: {} -> {} damage={} cooldown={} messages={}", tier.getName(), result,
                    player.getMainHandItem().getDamageValue(), player.getCooldowns().isOnCooldown(Prospectors.get(tier)), messages);
        }
        ticks = 0;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || ticks < 0 || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticks == 100) {
            LOGGER.info("Server smoke test: ran 100 ticks, stopping");
            event.getServer().halt(false);
        }
    }
}
