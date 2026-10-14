package com.example.curses.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.Items;

/**
 * Piglins normally throw away whatever is in their off hand once they stop admiring something (that is
 * what {@code StopHoldingItemIfNoLongerAdmiring} does every tick). This keeps a Totem of Undying (Hypocrisy
 * curse) or a Shield (Bastion curse) in place so it can show in the piglin's hand. Everything else a piglin
 * holds is untouched.
 */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {
    @Inject(method = "stopHoldingOffHandItem", at = @At("HEAD"), cancellable = true)
    private static void curses$keepCurseItems(Piglin piglin, boolean shouldBarter, CallbackInfo ci) {
        if (piglin.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || piglin.getOffhandItem().is(Items.SHIELD)) {
            ci.cancel();
        }
    }
}
