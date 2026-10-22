package com.lothrazar.prospectors;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.Collection;

public class ItemProspector extends Item {

    private final ProspectorTier type;

    public ItemProspector(ProspectorTier type) {
        // The real durability comes from the config (getMaxDamage); this only makes the item damageable and unstackable
        super(new Item.Properties().durability(ProspectorConfig.Tier.DEFAULT_DURABILITY));
        this.type = type;
    }

    public ProspectorTier getType() {
        return type;
    }

    public ProspectorConfig.Tier getSettings() {
        return ProspectorConfig.get(type);
    }

    /**
     * Right click on a block: search, report, then take one durability and start the cooldown, whether or not
     * anything was found (1.12 {@code onItemUse}/{@code onSuccess}). The game does not call this while the item is
     * cooling down for this player. The search and the messages run on the server only; the client just swings
     * the arm.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (!level.isClientSide) {
            ProspectorConfig.Tier settings = getSettings();
            int range = settings.range.get();
            Collection<ProspectorSearch.Found> found = ProspectorSearch.search(level, context.getClickedPos(), context.getClickedFace(),
                    range, settings.matcher(), settings.isBlacklist.get());
            if (found.isEmpty()) {
                player.sendSystemMessage(Component.translatable("prospector.none").append(String.valueOf(range)));
            }
            for (ProspectorSearch.Found entry : found) {
                player.sendSystemMessage(Component.translatable("prospector.found").append(entry.getName()).append(" " + entry.getCount()));
            }
            ItemStack stack = context.getItemInHand();
            stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(context.getHand()));
            int cooldown = settings.cooldown.get();
            if (cooldown > 0) {
                player.getCooldowns().addCooldown(this, cooldown);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        return getSettings().getDurability();
    }
}
