package com.example.curses.effect;

import java.util.EnumSet;
import java.util.List;

import com.example.curses.CursesMod;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * PICKUP phase of Payload: a phantom that is hunting a player looks for a creeper near that player, dives to it
 * and grabs it. The moment the creeper is mounted this goal ends and {@link PayloadHandler#beginCarry} takes
 * over, because a goal cannot carry the creeper: vanilla switches off MOVE goals while a mob rides the phantom.
 *
 * Creepers are searched for around the PLAYER, not the phantom: a hunting phantom circles 20-40 blocks above
 * its target, so a search centred on the phantom almost never finds anything on the ground.
 *
 * Phantoms steer by {@code moveTargetPoint} (made public by the access transformer), not by the usual wanted
 * position, so that is what this goal sets every tick.
 */
public class PayloadGoal extends Goal {
    private static final double MAX_PLAYER_DIST = 64.0; // only when its target is this close
    private static final double CREEPER_RANGE_H = 24.0; // creepers this far (sideways) from the player qualify
    private static final double CREEPER_RANGE_V = 16.0; // ... and this far up/down
    private static final double MIN_PLAYER_GAP_SQR = 7.0 * 7.0; // skip creepers about to blow up on the player anyway
    private static final float CHANCE = 0.5F;           // per search
    private static final int SEARCH_INTERVAL = 20;      // ticks between searches
    private static final int COOLDOWN = 400;            // ticks before the same phantom does it again
    private static final int PICKUP_TIMEOUT = 300;
    private static final double GRAB_DIST_SQR = 3.0 * 3.0;

    private final Phantom phantom;
    private Creeper creeper;
    private Player victim;
    private int ticks;
    private int searchDelay;
    private long readyAt;
    private boolean done;

    public PayloadGoal(Phantom phantom) {
        this.phantom = phantom;
        this.searchDelay = phantom.getRandom().nextInt(SEARCH_INTERVAL); // stagger
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!phantom.isAlive() || phantom.isVehicle() || !PayloadHandler.enabled(phantom.level())) {
            return false;
        }
        if (phantom.level().getGameTime() < readyAt) {
            return false;
        }
        if (--searchDelay > 0) {
            return false;
        }
        searchDelay = SEARCH_INTERVAL;

        LivingEntity target = phantom.getTarget();
        if (!(target instanceof Player player) || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (phantom.distanceToSqr(player) > MAX_PLAYER_DIST * MAX_PLAYER_DIST) {
            return false;
        }
        if (phantom.getRandom().nextFloat() >= CHANCE) {
            return false;
        }

        Level level = phantom.level();
        AABB area = player.getBoundingBox().inflate(CREEPER_RANGE_H, CREEPER_RANGE_V, CREEPER_RANGE_H);
        List<Creeper> found = level.getEntitiesOfClass(
                Creeper.class,
                area,
                c -> c.isAlive()
                        && !c.isPassenger()
                        && !c.isVehicle()
                        && !c.isIgnited()
                        && !c.getPersistentData().getBoolean(PayloadHandler.CARRIED)
                        && c.distanceToSqr(player) >= MIN_PLAYER_GAP_SQR
                        && (level.canSeeSky(c.blockPosition()) || phantom.hasLineOfSight(c)));
        if (found.isEmpty()) {
            return false;
        }

        // The creeper closest to the phantom: the shortest dive.
        Creeper nearest = null;
        double best = Double.MAX_VALUE;
        for (Creeper c : found) {
            double d = phantom.distanceToSqr(c);
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        creeper = nearest;
        victim = player;
        CursesMod.LOGGER.debug("Payload: phantom {} going for creeper {} ({} creepers near player)",
                phantom.getId(), creeper.getId(), found.size());
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (done || !phantom.isAlive() || !PayloadHandler.enabled(phantom.level())) {
            return false;
        }
        if (creeper == null || !creeper.isAlive() || creeper.isPassenger()) {
            return false;
        }
        if (victim == null || !victim.isAlive() || victim.level() != phantom.level()) {
            return false;
        }
        return ticks < PICKUP_TIMEOUT;
    }

    @Override
    public void start() {
        ticks = 0;
        done = false;
    }

    @Override
    public void stop() {
        readyAt = phantom.level().getGameTime() + COOLDOWN;
        creeper = null;
        victim = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        if (creeper == null || victim == null) {
            return;
        }

        // Hover just above the creeper's head.
        phantom.moveTargetPoint = creeper.position().add(0.0, creeper.getBbHeight() + 0.4, 0.0);

        if (phantom.distanceToSqr(creeper) < GRAB_DIST_SQR) {
            if (creeper.startRiding(phantom, true)) {
                PayloadHandler.beginCarry(phantom, creeper, victim); // carrying continues outside the goal system
            } else {
                CursesMod.LOGGER.debug("Payload: phantom {} could not mount creeper {}", phantom.getId(), creeper.getId());
            }
            done = true;
        }
    }
}
