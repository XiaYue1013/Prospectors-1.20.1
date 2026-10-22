package com.lothrazar.prospectors;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Prospectors.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    /**
     * Back to the local config once the client leaves a server.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        for (ProspectorTier tier : ProspectorTier.values()) {
            ProspectorConfig.get(tier).setSyncedDurability(0);
        }
    }
}
