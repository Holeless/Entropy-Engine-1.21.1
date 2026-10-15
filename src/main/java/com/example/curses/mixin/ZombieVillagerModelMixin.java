package com.example.curses.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.curses.client.ToolPose;

import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.ZombieVillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.monster.Zombie;

/** Client only. Same as {@link ZombieModelMixin}, for zombie villagers (their model has its own pose code). */
@Mixin(ZombieVillagerModel.class)
public abstract class ZombieVillagerModelMixin {
    private static final String SETUP = "setupAnim(Lnet/minecraft/world/entity/monster/Zombie;FFFFF)V";

    @Inject(method = SETUP, at = @At("HEAD"), require = 0)
    private void curses$begin(Zombie entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        ToolPose.begin(entity);
    }

    @Inject(method = SETUP, at = @At("RETURN"), require = 0)
    private void curses$end(Zombie entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
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
