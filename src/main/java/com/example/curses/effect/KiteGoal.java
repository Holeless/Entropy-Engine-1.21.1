package com.example.curses.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Kiting, backpedal part: while a ranged mob is attacking you and you are inside its kiting range, it walks
 * backwards away from you <b>while facing you</b>.
 *
 * <p>It uses the same "strafe" movement vanilla skeletons use to circle you, with a backwards component, so the
 * body stays turned toward the player the whole time. It takes no goal flags, so the vanilla attack goal keeps
 * running underneath it (drawing the bow, aiming, shooting); this goal only decides where the feet go, and
 * since it was added after the attack goal it ticks after it and wins the strafe each tick.</p>
 *
 * <p>If the way back is blocked (wall, drop, fire, water) it sidesteps instead; if that is blocked too it just
 * stands and shoots. It steps aside for {@link HideGoal} while that is running. Does nothing while the curse is
 * off.</p>
 */
public class KiteGoal extends Goal {
    /** Fraction of the mob's normal walking speed it backs away at. */
    private static final double SPEED = 0.7;
    /** Once backing away, keep going until this much past the start distance, so it does not jitter. */
    private static final double HYSTERESIS = 1.5;
    /** Spots this far behind the mob must be safe to stand on. */
    private static final double STEP_NEAR = 0.9;
    private static final double STEP_FAR = 1.7;

    private final Mob mob;
    private Player target;
    private boolean sideLeft = true;

    public KiteGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        return shouldKite(false);
    }

    @Override
    public boolean canContinueToUse() {
        return shouldKite(true);
    }

    private boolean shouldKite(boolean continuing) {
        if (!mob.isAlive() || mob.isPassenger() || !mob.onGround()
                || KitingHandler.HIDING.contains(mob) || !KitingHandler.enabled(mob.level())) {
            return false;
        }
        if (!(mob.getTarget() instanceof Player player) || !player.isAlive() || player.isSpectator() || player.isCreative()) {
            return false;
        }
        if (!KitingHandler.isArmed(mob)) {
            return false;
        }
        double range = KitingHandler.kiteRange(mob) + (continuing ? HYSTERESIS : 0.0);
        if (mob.distanceToSqr(player) >= range * range || !mob.hasLineOfSight(player)) {
            return false;
        }
        target = player;
        return true;
    }

    @Override
    public void stop() {
        target = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        // Keep facing the player: this is what makes it a backpedal and not a turn-and-run.
        mob.lookAt(target, 30.0F, 30.0F);
        // A path left over from the attack goal would take the movement back from the strafe.
        mob.getNavigation().stop();

        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3) {
            return;
        }
        double toX = dx / length;
        double toZ = dz / length;

        if (canStep(-toX, -toZ)) {
            move(-1.0F, 0.0F); // straight back
            return;
        }
        // Blocked behind: sidestep. In the game's controls a positive strafe is to the left.
        double leftX = toZ;
        double leftZ = -toX;
        boolean leftOk = canStep(leftX, leftZ);
        boolean rightOk = canStep(-leftX, -leftZ);
        if (sideLeft && !leftOk) {
            sideLeft = false;
        } else if (!sideLeft && !rightOk) {
            sideLeft = true;
        }
        if (sideLeft ? leftOk : rightOk) {
            move(0.0F, sideLeft ? 1.0F : -1.0F);
        }
    }

    private void move(float forward, float strafe) {
        MoveControl control = mob.getMoveControl();
        control.strafe(forward, strafe);
        // strafe() fixes the speed at a crawl (a quarter); raise it (access transformer) so the retreat is real.
        control.speedModifier = SPEED;
    }

    /** Is it safe to walk a couple of blocks in this horizontal direction? */
    private boolean canStep(double dirX, double dirZ) {
        int height = Mth.ceil(mob.getBbHeight());
        for (double distance : new double[] {STEP_NEAR, STEP_FAR}) {
            BlockPos feet = BlockPos.containing(mob.getX() + dirX * distance, mob.getY(), mob.getZ() + dirZ * distance);
            if (!mob.level().hasChunkAt(feet) || !KitingHandler.standable(mob.level(), feet, height)) {
                return false;
            }
        }
        return true;
    }
}
