package com.lothrazar.prospectors;

import com.lothrazar.prospectors.network.ProspectorNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.EnumMap;
import java.util.Map;

@Mod(Prospectors.MODID)
public class Prospectors {

    public static final String MODID = "prospectors";

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final Map<ProspectorTier, RegistryObject<ItemProspector>> PROSPECTORS = new EnumMap<>(ProspectorTier.class);

    static {
        for (ProspectorTier tier : ProspectorTier.values()) {
            PROSPECTORS.put(tier, ITEMS.register(tier.getName(), () -> new ItemProspector(tier)));
        }
    }

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register(MODID, () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + MODID))
            .icon(() -> new ItemStack(Items.DIAMOND))
            .displayItems((parameters, output) -> PROSPECTORS.values().forEach(item -> output.accept(item.get())))
            .build());

    public Prospectors(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        ITEMS.register(modBus);
        TABS.register(modBus);
        context.registerConfig(ModConfig.Type.COMMON, ProspectorConfig.SPEC);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onConfigLoading);
        modBus.addListener(this::onConfigReloading);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onTagsUpdated);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        if (!FMLEnvironment.production) {
            devOnlyContent(modBus);
        }
    }

    public static ItemProspector get(ProspectorTier tier) {
        return PROSPECTORS.get(tier).get();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        ProspectorNetwork.register();
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ProspectorConfig.SPEC) {
            ProspectorConfig.invalidate();
        }
    }

    /**
     * The file was edited while the game runs: the new values apply from the next use on.
     */
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ProspectorConfig.SPEC) {
            return;
        }
        ProspectorConfig.invalidate();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> {
                ProspectorConfig.parseAll();
                ProspectorNetwork.syncDurabilityToAll();
            });
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        ProspectorConfig.parseAll();
        ProspectorConfig.warnUnknownTags();
    }

    /**
     * {@code /reload} may have added or removed tags.
     */
    private void onTagsUpdated(TagsUpdatedEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD && server != null && server.isReady()) {
            ProspectorConfig.warnUnknownTags();
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProspectorNetwork.syncDurability(player);
        }
    }

    /**
     * Development runs only: the GameTests need an ore block from "another mod". That class is not in the release jar.
     */
    private static void devOnlyContent(IEventBus modBus) {
        try {
            Class.forName("com.lothrazar.prospectors.dev.TestOreMod").getMethod("register", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException e) {
            // not there in a release jar
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
