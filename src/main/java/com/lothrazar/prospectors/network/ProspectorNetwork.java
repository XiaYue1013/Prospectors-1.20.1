package com.lothrazar.prospectors.network;

import com.lothrazar.prospectors.Prospectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ProspectorNetwork {

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(Prospectors.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ProspectorNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(SyncDurabilityPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncDurabilityPacket::encode)
                .decoder(SyncDurabilityPacket::decode)
                .consumerMainThread(SyncDurabilityPacket::handle)
                .add();
    }

    public static void syncDurability(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), SyncDurabilityPacket.fromConfig());
    }

    public static void syncDurabilityToAll() {
        CHANNEL.send(PacketDistributor.ALL.noArg(), SyncDurabilityPacket.fromConfig());
    }
}
