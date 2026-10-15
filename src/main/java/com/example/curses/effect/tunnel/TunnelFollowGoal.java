package com.example.curses.effect.tunnel;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Omniscience: every hostile mob that is near the shared tunnel to its player walks it, instead of standing at a
 * wall. All of them read the same {@link TunnelPlan}, so they all take the same route.
 *
 * <p>Mobs never path along the whole tunnel (vanilla only searches about 16 blocks): they walk to a waypoint a
 * few cells ahead of where they are, and pick the next one when they get there. Roles:</p>
 * <ul>
 *   <li><b>creepers</b> go to the very head of the tunnel, where they can be primed to open the next stretch;</li>
 *   <li><b>witches</b> hold back about {@link #WITCH_BACKOFF} cells from the head, out of reach of the blast they
 *       throw at it;</li>
 *   <li><b>everything else</b> walks to the head and waits behind it.</li>
 * </ul>
 * <p>The goal lets go when the mob is close to the player (vanilla melee / ranged AI takes over), and path
 * searches are rationed by {@link TunnelManager#takeNav()}. Does nothing while the curse is off.</p>
 */
public class TunnelFollowGoal extends Goal {
    private static final int DECIDE_CALLS = 5;            // canUse calls (every 2 ticks) between looks
    private static final double JOIN_RADIUS_SQR = 20.0 * 20.0;
    private static final double INSIDE_SQR = 1.8 * 1.8;
    private static final int LOOKAHEAD = 6;
    private static final int WITCH_BACKOFF = 9;
    private static final int RENAV_TICKS = 8;
    private static final double SPEED = 1.0;

    private final Mob mob;
    private int delay;
    private int renav;
    private Player player;
    private TunnelPlan plan;
    private BlockPos lastWaypoint;

    public TunnelFollowGoal(Mob mob) {
        this.mob = mob;
        this.delay = mob.getRandom().nextInt(DECIDE_CALLS);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private double release() {
        return mob instanceof RangedAttackMob ? 12.0 : 5.0;
    }

    @Override
    public boolean canUse() {
        if (!TunnelManager.working() || !mob.isAlive() || mob.isPassenger() || fusing()) {
            return false;
        }
        if (--delay > 0) {
            return false;
        }
        delay = DECIDE_CALLS;
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        Player prey = TunnelManager.preyFor(mob, level);
        if (prey == null) {
            return false;
        }
        TunnelPlan found = TunnelManager.planFor(prey);
        if (found == null || found.level != level || mob.distanceToSqr(prey) < release() * release()) {
            return false;
        }
        if (found.nearestOpen(mob.position(), JOIN_RADIUS_SQR) < 0) {
            return false;
        }
        this.player = prey;
        this.plan = found;
        this.renav = 0;
        this.lastWaypoint = null;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        double keep = release() * 0.8;
        return TunnelManager.isActive() && mob.isAlive() && !mob.isPassenger() && !fusing()
                && plan != null && !plan.dead && player != null && player.isAlive()
                && !player.isCreative() && !player.isSpectator() && player.level() == mob.level()
                && mob.distanceToSqr(player) >= keep * keep;
    }

    /** A creeper that is already burning its fuse stands still (the shared breach is about to happen). */
    private boolean fusing() {
        return mob instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwellDir() > 0);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        plan = null;
        player = null;
        lastWaypoint = null;
    }

    @Override
    public void tick() {
        if (plan == null || player == null || --renav > 0) {
            return;
        }
        renav = RENAV_TICKS;
        plan.lastActive = mob.level().getGameTime();

        BlockPos waypoint = waypoint();
        if (waypoint == null) {
            return;
        }
        boolean changed = !waypoint.equals(lastWaypoint);
        if (!changed && (mob.getNavigation().isInProgress()
                || mob.distanceToSqr(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5) < INSIDE_SQR)) {
            return; // on its way, or already there and waiting
        }
        if (!TunnelManager.takeNav()) {
            renav = 2; // try again very soon
            return;
        }
        lastWaypoint = waypoint;
        mob.getNavigation().moveTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, SPEED);
    }

    private BlockPos waypoint() {
        Vec3 pos = mob.position();
        int idx = plan.nearestOpen(pos, JOIN_RADIUS_SQR);
        if (idx < 0) {
            return null;
        }
        int last = plan.head - 1;
        int target = last;
        if (mob instanceof Witch && plan.frontierPending()) {
            target = Math.max(0, last - WITCH_BACKOFF);
        }

        BlockPos nearest = plan.cells.get(idx);
        boolean inside = pos.distanceToSqr(nearest.getX() + 0.5, nearest.getY(), nearest.getZ() + 0.5) <= INSIDE_SQR;
        int wp;
        if (!inside) {
            wp = idx;                              // first get into the tunnel
        } else if (idx > target) {
            wp = target;                           // a witch that got too close backs off
        } else {
            wp = Math.min(idx + LOOKAHEAD, target);
        }
        return plan.cells.get(wp);
    }
}
