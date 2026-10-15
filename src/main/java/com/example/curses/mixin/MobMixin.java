package com.example.curses.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.curses.effect.BeastOfBurdenHandler;
import com.example.curses.effect.SunproofHandler;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Spider;

/**
 * Two small hooks into {@code Mob}.
 *
 * <ul>
 *   <li><b>Sunproof:</b> {@code isSunBurnTick()} answers "no" while the curse is on, so nothing burns in sunlight.</li>
 *   <li><b>Beast of Burden:</b> who is in control of a mount.
 *     <ul>
 *       <li>Horse, donkey, mule: the rider steers. The mount's own movement goals are held off every tick (vanilla
 *           only re-evaluates that every five ticks), so it never wanders or stops on its own.</li>
 *       <li>Spider: the spider steers. Vanilla hands a rider the mount's navigation and move control; a rider on a
 *           spider keeps its own instead, and the spider's own goals are kept on, so the spider hunts by itself
 *           while the rider shoots or fights from its back.</li>
 *     </ul></li>
 * </ul>
 */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Shadow
    protected PathNavigation navigation;
    @Shadow
    protected MoveControl moveControl;
    @Shadow
    protected JumpControl jumpControl;
    @Shadow
    @Final
    protected GoalSelector goalSelector;

    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void curses$sunproof(CallbackInfoReturnable<Boolean> cir) {
        if (SunproofHandler.isActive()) {
            cir.setReturnValue(false);
        }
    }

    /** A mob riding a spider (Beast of Burden) does not borrow the spider's navigation. */
    @Inject(method = "getNavigation", at = @At("HEAD"), cancellable = true, require = 0)
    private void curses$ownNavigation(CallbackInfoReturnable<PathNavigation> cir) {
        if (curses$ridesSpider()) {
            cir.setReturnValue(this.navigation);
        }
    }

    @Inject(method = "getMoveControl", at = @At("HEAD"), cancellable = true, require = 0)
    private void curses$ownMoveControl(CallbackInfoReturnable<MoveControl> cir) {
        if (curses$ridesSpider()) {
            cir.setReturnValue(this.moveControl);
        }
    }

    @Inject(method = "getJumpControl", at = @At("HEAD"), cancellable = true, require = 0)
    private void curses$ownJumpControl(CallbackInfoReturnable<JumpControl> cir) {
        if (curses$ridesSpider()) {
            cir.setReturnValue(this.jumpControl);
        }
    }

    private boolean curses$ridesSpider() {
        Mob self = (Mob) (Object) this;
        return self.isPassenger() && self.getVehicle() instanceof Spider && BeastOfBurdenHandler.enabled(self.level());
    }

    /** Mounts that carry a Beast of Burden rider: horses obey it, spiders do not. */
    @Inject(method = "updateControlFlags", at = @At("HEAD"), cancellable = true, require = 0)
    private void curses$mountControl(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (self.level().isClientSide() || !BeastOfBurdenHandler.isMountType(self)
                || !(self.getFirstPassenger() instanceof Mob rider) || !BeastOfBurdenHandler.isRider(rider)
                || !BeastOfBurdenHandler.enabled(self.level())) {
            return;
        }
        boolean own = self instanceof Spider; // a spider keeps its own goals, a horse gives them up
        this.goalSelector.setControlFlag(Goal.Flag.MOVE, own);
        this.goalSelector.setControlFlag(Goal.Flag.LOOK, own);
        this.goalSelector.setControlFlag(Goal.Flag.JUMP, own);
        ci.cancel();
    }
}
