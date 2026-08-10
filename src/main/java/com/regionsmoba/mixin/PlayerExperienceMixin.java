package com.regionsmoba.mixin;

import com.regionsmoba.classes.impl.EnchanterAbility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales experience gains before they land.
 *
 * The Nether Enchanter earns 2x XP from all sources, and their Intensifier
 * grants nearby teammates 1.25x for its duration
 * (docs/src-md/classes/nether-classes.md). Both multipliers are applied here
 * rather than at each XP source, because vanilla awards XP from ore breaking,
 * smelting, breeding, trading, and mob kills through completely separate paths —
 * this single funnel is the only place that catches all of them.
 */
@Mixin(Player.class)
public abstract class PlayerExperienceMixin {

    @ModifyVariable(method = "giveExperiencePoints", at = @At("HEAD"), argsOnly = true)
    private int regionsmoba$scaleExperience(int amount) {
        Object self = this;
        if (!(self instanceof ServerPlayer player)) return amount;
        return EnchanterAbility.scaleExperience(player, amount);
    }
}
