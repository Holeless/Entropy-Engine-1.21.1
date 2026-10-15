package com.example.curses.effect;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;

/**
 * Beast of Burden, spider jockeys: a spider carrying a ranged mob (skeleton, stray, pillager...) is a climbing unit
 * while it travels and a firing platform once the player is in range.
 *
 * <ul>
 *   <li><b>Travelling:</b> outside the rider's firing range this goal does nothing, so the spider hunts with its own
 *       goals and climbs walls like any spider.</li>
 *   <li><b>In firing range</b> (with a line of sight, and not while climbing): the spider stops charging. It holds
 *       its ground facing the player so the rider can shoot, and if the player comes closer than the rider's kiting
 *       range it backs away, facing the player, at {@link KitingHandler#BACKPEDAL_SPEED} of its walking pace (the
 *       same strafe backpedal {@link KiteGoal} gives ranged mobs). If the way back is blocked it sidesteps.</li>
 * </ul>
 *
 * <p>Priority 2 with the MOVE and LOOK flags, so while it runs the spider's own melee/chase goals are paused. Added
 * to every spider; does nothing while Beast of Burden is off or the spider has no armed ranged rider.</p>
 */
public class JockeyKiteGoal extends Goal {
    private static final double HYSTERESIS = 1.0;
    private static final double RANGE_HYSTERESIS = 2.0;
    private static final double STEP_NEAR = 1.1;
    private static final double STEP_FAR = 2.0;

    private final Spider spider;
    private Mob rider;
    private Player target;
    private boolean sideLeft = true;
    private boolean backing;

    public JockeyKiteGoal(Spider spider) {
        this.spider = spider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return check(false);
    }

    @Override
    public boolean canContinueToUse() {
        return check(true);
    }

    private boolean check(boolean continuing) {
        if (!spider.isAlive() || !BeastOfBurdenHandler.enabled(spider.level())) {
            return false;
        }
        if (!(spider.getFirstPassenger() instanceof Mob passenger) || !BeastOfBurdenHandler.isRider(passenger)
                || !(passenger instanceof RangedAttackMob) || !KitingHandler.isArmed(passenger)) {
            return false;
        }
        // Climbing is the spider's job: let it carry on up the wall.
        if (spider.onClimbable() || (!continuing && !spider.onGround())) {
            return false;
        }
        LivingEntity wanted = spider.getTarget() != null ? spider.getTarget() : passenger.getTarget();
        if (!(wanted instanceof Player player) || !player.isAlive() || player.isSpectator() || player.isCreative()
                || player.level() != spider.level()) {
            return false;
        }
        double fire = KitingHandler.fireRange(passenger) + (continuing ? RANGE_HYSTERESIS : 0.0);
        if (spider.distanceToSqr(player) > fire * fire || !spider.hasLineOfSight(player)) {
            return false;
        }
        rider = passenger;
        target = player;
        return true;
    }

    @Override
    public void start() {
        backing = false;
    }

    @Override
    public void stop() {
        rider = null;
        target = null;
        backing = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (rider == null || target == null) {
            return;
        }
        spider.lookAt(target, 30.0F, 30.0F);
        spider.getNavigation().stop();

        double range = KitingHandler.kiteRange(rider) + (backing ? HYSTERESIS : 0.0);
        if (spider.distanceToSqr(target) >= range * range) {
            backing = false;
            move(0.0F, 0.0F); // in range, not too close: hold still and let the rider shoot
            return;
        }
        backing = true;

        double dx = target.getX() - spider.getX();
        double dz = target.getZ() - spider.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3) {
            return;
        }
        double toX = dx / length;
        double toZ = dz / length;

        if (canStep(-toX, -toZ)) {
            move(-1.0F, 0.0F);
            return;
        }
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
        } else {
            move(0.0F, 0.0F);
        }
    }

    private void move(float forward, float strafe) {
        MoveControl control = spider.getMoveControl();
        control.strafe(forward, strafe);
        control.speedModifier = KitingHandler.BACKPEDAL_SPEED;
    }

    private boolean canStep(double dirX, double dirZ) {
        int height = Mth.ceil(spider.getBbHeight());
        for (double distance : new double[] {STEP_NEAR, STEP_FAR}) {
            BlockPos feet = BlockPos.containing(spider.getX() + dirX * distance, spider.getY(), spider.getZ() + dirZ * distance);
            if (!spider.level().hasChunkAt(feet) || !KitingHandler.standable(spider.level(), feet, height)) {
                return false;
            }
        }
        return true;
    }
}
