package com.example.curses.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.curses.client.ToolPose;

import net.minecraft.client.model.AbstractZombieModel;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.monster.Monster;

/**
 * Client only. Zombie, husk and drowned models: a zombie holding a pickaxe or an axe skips the "arms stretched
 * out" zombie pose, so the arms keep the humanoid pose a player has (walking swing, and the arm swing a player
 * does when attacking or mining). Everyone else is untouched. {@code require = 0}: if this stops matching, the mod
 * still loads and the zombie just keeps its normal arms.
 */
@Mixin(AbstractZombieModel.class)
public abstract class ZombieModelMixin {
    private static final String SETUP = "setupAnim(Lnet/minecraft/world/entity/monster/Monster;FFFFF)V";

    @Inject(method = SETUP, at = @At("HEAD"), require = 0)
    private void curses$begin(Monster entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        ToolPose.begin(entity);
    }

    @Inject(method = SETUP, at = @At("RETURN"), require = 0)
    private void curses$end(Monster entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        ToolPose.end();
    }

    @Redirect(method = SETUP,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;animateZombieArms(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;ZFF)V"),
            require = 0)
    private void curses$arms(ModelPart left, ModelPart right, boolean aggressive, float attackTime, float ageInTicks) {
        if (!ToolPose.usePlayerPose()) {
            AnimationUtils.animateZombieArms(left, right, aggressive, attackTime, ageInTicks);
        }
    }
}
