package com.lothrazar.prospectors.network;

import com.lothrazar.prospectors.ProspectorConfig;
import com.lothrazar.prospectors.ProspectorTier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the durability of each tier from the server's config. In 1.12 every side used its own config
 * file; the durability is the only setting a client needs (for the durability bar), the server does everything else.
 */
public record SyncDurabilityPacket(int[] durability) {

    public static SyncDurabilityPacket fromConfig() {
        int[] durability = new int[ProspectorTier.values().length];
        for (ProspectorTier tier : ProspectorTier.values()) {
            durability[tier.ordinal()] = ProspectorConfig.get(tier).durability.get();
        }
        return new SyncDurabilityPacket(durability);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarIntArray(durability);
    }

    public static SyncDurabilityPacket decode(FriendlyByteBuf buf) {
        return new SyncDurabilityPacket(buf.readVarIntArray(ProspectorTier.values().length));
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        for (ProspectorTier tier : ProspectorTier.values()) {
            if (tier.ordinal() < durability.length) {
                ProspectorConfig.get(tier).setSyncedDurability(Math.max(0, durability[tier.ordinal()]));
            }
        }
        context.get().setPacketHandled(true);
    }
}
