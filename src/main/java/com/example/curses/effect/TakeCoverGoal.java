package com.example.curses.effect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fortified: an Iron Golem that is hunting a player it cannot reach (the player is on a pillar, in a hole, behind
 * glass...) stops standing around under them and takes cover instead.
 *
 * <p>"Cannot reach" means the golem's own pathfinding cannot get to the player. It has to stay that way for a
 * few checks in a row (about 1.5 seconds) so that a jumping player does not trigger it. While it holds:</p>
 * <ol>
 *   <li>The golem looks for a nearby spot (up to 12 blocks away) that it can walk to, that is at least 8 blocks
 *       from the player sideways (so never right under a pillar), and where blocks stand between the player's
 *       eyes and both the golem's legs and head. That is cover from arrows.</li>
 *   <li>If there is no such spot it backs away from the player until it is 24 blocks off.</li>
 *   <li>Once safe it stays put. As soon as the player can be reached again the goal ends and the golem's normal
 *       attack goals take over.</li>
 * </ol>
 *
 * <p>Added to every Iron Golem; it does nothing while the Fortified curse is off. Priority 0 so it beats the
 * golem's own movement goals, which share the MOVE flag.</p>
 */
public class TakeCoverGoal extends Goal {
    private static final double MAX_PLAYER_DIST = 48.0;
    private static final int START_CHECK_CALLS = 5;   // canUse calls between reachability checks (about 10 ticks)
    private static final int RUN_CHECK_TICKS = 10;    // ticks between checks while the goal is running
    private static final int UNREACHABLE_CHECKS = 3;  // consecutive "cannot reach" results before taking cover
    private static final int REACHABLE_CHECKS = 2;    // consecutive "can reach" results before giving up cover
    private static final double SAFE_DISTANCE = 8.0;  // closer than this to the player (sideways) is never cover
    private static final double RETREAT_DISTANCE = 24.0;
    private static final int SEARCH_TRIES = 24;
    private static final int SEARCH_RADIUS = 12;
    private static final int PATH_VALIDATIONS = 3;    // best candidates that get a (costly) path check
    private static final double SPEED = 1.1;

    private record Candidate(BlockPos feet, Vec3 position, double score) {
    }

    private final IronGolem golem;
    private Player player;
    private int startDelay;
    private int unreachableStreak;
    private int reachableStreak;
    private int runDelay;
    private Vec3 destination;
    private boolean destinationIsCover;
    private boolean done;

    public TakeCoverGoal(IronGolem golem) {
        this.golem = golem;
        this.startDelay = golem.getRandom().nextInt(START_CHECK_CALLS); // stagger
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!golem.isAlive() || !FortifiedHandler.enabled(golem.level())) {
            unreachableStreak = 0;
            return false;
        }
        if (!(golem.getTarget() instanceof Player target) || !target.isAlive() || target.isSpectator()
                || golem.distanceToSqr(target) > MAX_PLAYER_DIST * MAX_PLAYER_DIST) {
            unreachableStreak = 0;
            return false;
        }
        if (--startDelay > 0) {
            return false;
        }
        startDelay = START_CHECK_CALLS;

        if (canReach(target)) {
            unreachableStreak = 0;
            return false;
        }
        if (++unreachableStreak < UNREACHABLE_CHECKS) {
            return false;
        }
        player = target;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !done
                && golem.isAlive()
                && FortifiedHandler.enabled(golem.level())
                && player != null
                && player.isAlive()
                && !player.isSpectator()
                && golem.getTarget() == player
                && golem.distanceToSqr(player) <= MAX_PLAYER_DIST * MAX_PLAYER_DIST * 2.25;
    }

    @Override
    public void start() {
        done = false;
        reachableStreak = 0;
        runDelay = 0;
        destination = null;
        destinationIsCover = false;
    }

    @Override
    public void stop() {
        golem.getNavigation().stop();
        player = null;
        destination = null;
        unreachableStreak = 0;
        startDelay = START_CHECK_CALLS;
        done = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (player == null) {
            return;
        }
        golem.getLookControl().setLookAt(player, 30.0F, 30.0F);
        if (--runDelay > 0) {
            return;
        }
        runDelay = RUN_CHECK_TICKS;

        // The player can be reached again (came down, jumped off...): give the golem back to vanilla.
        if (canReach(player)) {
            if (++reachableStreak >= REACHABLE_CHECKS) {
                done = true;
            }
            return;
        }
        reachableStreak = 0;

        // Already out of sight and away from the pillar: stay put.
        if (isSafe(golem.position())) {
            golem.getNavigation().stop();
            destination = null;
            return;
        }

        // Still on its way to a spot that is good: keep going.
        if (destination != null && !golem.getNavigation().isDone() && (!destinationIsCover || isSafe(destination))) {
            return;
        }

        Vec3 cover = findCover();
        if (cover != null) {
            go(cover, true);
            return;
        }
        if (horizontalDistance(golem.position(), player) < RETREAT_DISTANCE) {
            Vec3 away = DefaultRandomPos.getPosAway(golem, 16, 7, player.position());
            if (away != null) {
                go(away, false);
                return;
            }
        }
        golem.getNavigation().stop();
        destination = null;
    }

    private void go(Vec3 target, boolean cover) {
        destination = target;
        destinationIsCover = cover;
        golem.getNavigation().moveTo(target.x, target.y, target.z, SPEED);
    }

    private boolean canReach(Player target) {
        Path path = golem.getNavigation().createPath(target, 0);
        return path != null && path.canReach();
    }

    /** Far enough from the player sideways, and blocks between the player's eyes and the golem's legs and head. */
    private boolean isSafe(Vec3 feet) {
        if (horizontalDistance(feet, player) < SAFE_DISTANCE) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        return blocked(eye, feet.add(0.0, 0.8, 0.0)) && blocked(eye, feet.add(0.0, 2.2, 0.0));
    }

    private boolean blocked(Vec3 from, Vec3 to) {
        return golem.level()
                .clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                .getType() != HitResult.Type.MISS;
    }

    /** A spot the golem can walk to from which the player cannot see it, or null. */
    private Vec3 findCover() {
        Level level = golem.level();
        RandomSource random = golem.getRandom();
        BlockPos origin = golem.blockPosition();

        List<Candidate> found = new ArrayList<>();
        for (int i = 0; i < SEARCH_TRIES; i++) {
            int x = origin.getX() + random.nextIntBetweenInclusive(-SEARCH_RADIUS, SEARCH_RADIUS);
            int z = origin.getZ() + random.nextIntBetweenInclusive(-SEARCH_RADIUS, SEARCH_RADIUS);
            BlockPos feet = groundAt(level, x, origin.getY(), z);
            if (feet == null) {
                continue;
            }
            Vec3 position = Vec3.atBottomCenterOf(feet);
            if (!isSafe(position)) {
                continue;
            }
            // Close to the golem is quick shelter; a bit further from the player is better.
            double score = -position.distanceTo(golem.position()) + 0.25 * horizontalDistance(position, player);
            found.add(new Candidate(feet, position, score));
        }
        found.sort((a, b) -> Double.compare(b.score(), a.score()));

        int checked = 0;
        for (Candidate candidate : found) {
            if (checked++ >= PATH_VALIDATIONS) {
                break;
            }
            Path path = golem.getNavigation().createPath(candidate.feet(), 0);
            if (path != null && path.canReach()) {
                return candidate.position();
            }
        }
        return null;
    }

    /** Standable spot in this column near the given height (solid floor, 3 blocks of room), or null. */
    private static BlockPos groundAt(Level level, int x, int y, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.hasChunkAt(pos)) {
                return null;
            }
            BlockPos below = pos.below();
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                    && isFree(level, pos) && isFree(level, pos.above()) && isFree(level, pos.above(2))) {
                return pos;
            }
        }
        return null;
    }

    private static boolean isFree(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    private static double horizontalDistance(Vec3 from, Player to) {
        double dx = from.x - to.getX();
        double dz = from.z - to.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
