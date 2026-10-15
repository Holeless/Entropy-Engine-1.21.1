package com.example.curses.effect;

import java.util.EnumSet;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Beast of Burden: a hostile mob that is hunting a player who is still far away looks for a free horse, donkey,
 * mule or spider nearby, walks to it and climbs on. From then on the mount does the walking (see
 * {@link BeastOfBurdenHandler}).
 *
 * <p>It only starts when a free mount is within {@link #SEARCH_RANGE} blocks and the mob can actually walk to it
 * (one path search per look, a look every second or so). One rider per mount. Does nothing while the curse is off.</p>
 */
public class SeekMountGoal extends Goal {
    private static final double SEARCH_RANGE = 16.0;
    private static final double MIN_PLAYER_DIST = 12.0;  // a mount is only worth it for a longer trip
    private static final double MAX_PLAYER_DIST = 64.0;
    private static final int SEARCH_CALLS = 10;          // canUse calls (every 2 ticks) between looks
    private static final int TIMEOUT = 300;              // ticks to reach the mount before giving up
    private static final int COOLDOWN = 200;             // ticks before trying again
    private static final double MOUNT_REACH_SQR = 2.5 * 2.5;
    private static final double SPEED = 1.15;

    private final Mob mob;
    private Mob mount;
    private int delay;
    private int ticks;
    private long readyAt;

    public SeekMountGoal(Mob mob) {
        this.mob = mob;
        this.delay = mob.getRandom().nextInt(SEARCH_CALLS); // stagger
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive() || mob.isPassenger() || mob.isVehicle() || !BeastOfBurdenHandler.enabled(mob.level())) {
            return false;
        }
        if (mob.level().getGameTime() < readyAt) {
            return false;
        }
        if (--delay > 0) {
            return false;
        }
        delay = SEARCH_CALLS;

        if (!(mob.getTarget() instanceof Player player) || !validPlayer(player)) {
            return false;
        }
        double distSqr = mob.distanceToSqr(player);
        if (distSqr < MIN_PLAYER_DIST * MIN_PLAYER_DIST || distSqr > MAX_PLAYER_DIST * MAX_PLAYER_DIST) {
            return false;
        }
        Mob found = BeastOfBurdenHandler.findMount(mob, SEARCH_RANGE);
        if (found == null) {
            return false;
        }
        Path path = mob.getNavigation().createPath(found, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        mount = found;
        BeastOfBurdenHandler.CLAIMED.add(found);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return mount != null && mob.isAlive() && !mob.isPassenger()
                && mount.isAlive() && !mount.isVehicle()
                && ticks < TIMEOUT
                && BeastOfBurdenHandler.enabled(mob.level())
                && mob.getTarget() instanceof Player player && validPlayer(player);
    }

    @Override
    public void start() {
        ticks = 0;
    }

    @Override
    public void stop() {
        if (mount != null) {
            BeastOfBurdenHandler.CLAIMED.remove(mount);
        }
        if (!mob.isPassenger()) {
            mob.getNavigation().stop();
        }
        mount = null;
        readyAt = mob.level().getGameTime() + COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (mount == null) {
            return;
        }
        ticks++;
        mob.getLookControl().setLookAt(mount, 30.0F, 30.0F);

        if (mob.distanceToSqr(mount) <= MOUNT_REACH_SQR) {
            if (mob.startRiding(mount, true)) {
                mount.setPersistenceRequired(); // a mount that carries someone does not wander off the map
            }
            return; // canContinueToUse ends the goal now (the mob is a passenger)
        }
        if (ticks % 10 == 1 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(mount, SPEED);
        }
    }

    private static boolean validPlayer(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
    }
}
