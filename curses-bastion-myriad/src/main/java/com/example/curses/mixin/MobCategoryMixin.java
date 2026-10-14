package com.example.curses.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.curses.effect.MyriadHandler;

import net.minecraft.world.entity.MobCategory;

/**
 * Vanilla only spawns hostile mobs while fewer than a cap of them (70 per player area at the default) exist.
 * While the Myriad curse is active that cap is multiplied, so the extra spawns are not immediately blocked by it.
 */
@Mixin(MobCategory.class)
public abstract class MobCategoryMixin {
    @Inject(method = "getMaxInstancesPerChunk", at = @At("RETURN"), cancellable = true)
    private void curses$myriadCap(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this == MobCategory.MONSTER && MyriadHandler.isCapMultiplied()) {
            cir.setReturnValue(cir.getReturnValue() * MyriadHandler.MULTIPLIER);
        }
    }
}
