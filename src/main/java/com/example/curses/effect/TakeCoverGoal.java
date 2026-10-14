package com.example.curses.effect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fortified: an Iron Golem that is hunting a player who is holding a bow (or crossbow, or trident) does not
 * walk into the arrows. It commits to one of two things and sticks with it:
 *
 * <ol>
 *   <li><b>Hide.</b> It looks for a spot it can walk to (up to 14 blocks away) where <i>no part of its body</i>
 *       can be seen from the player's eyes (checked with rays to the corners, middle and top of the golem),
 *       and that is at least 8 blocks from the player sideways. In a village that is the far side of a house.
 *       It runs there and stays put. If the player moves and the spot stops being hidden, it moves to a new
 *       one.</li>
 *   <li><b>Parry.</b> If there is nothing to hide behind (open field) it stands its ground facing the player,
 *       and arrows that hit it from the front are batted away most of the time (see
 *       {@link FortifiedHandler}).</li>
 * </ol>
 *
 * <p>The goal ends, and the golem goes back to its normal attack goals, when the player comes within
 * {@link #AMBUSH_DIST} blocks (it springs out at them), or when the player has not held a ranged weapon for
 * 5 seconds, or when the golem is no longer angry at them. After it ends it will not start again for 5
 * seconds, so it fights instead of running straight back to cover.</p>
 *
 * <p>Whether a player can be reached is deliberately not used any more: the golem's pathfinding only looks
 * about 16 blocks, so "can reach" flipped between yes and no as the golem moved and made it run back and
 * forth.</p>
 *
 * <p>Added to every Iron Golem; it does nothing while the Fortified curse is off. Priority 0 so it beats the
 * golem's own movement goals, which share the MOVE flag.</p>
 */
public class TakeCoverGoal extends Goal {
    private static final double MAX_PLAYER_DIST = 48.0;
    private static final double START_DIST = 10.0;      // only starts to hide when the player is at least this far
    private static final double AMBUSH_DIST = 6.0;      // the player gets this close: stop hiding and attack
    private static final double MIN_COVER_DIST = 8.0;   // cover must be this far from the player sideways
    private static final int START_CHECK_CALLS = 5;     // canUse calls between checks (about 10 ticks)
    private static final int ARMED_CHECKS = 2;          // consecutive checks with a ranged weapon before hiding
    private static final int RUN_CHECK_TICKS = 5;       // ticks between decisions while the goal is running
    private static final int UNARMED_RELEASE = 100;     // ticks without a ranged weapon before it comes out
    private static final int COOLDOWN = 100;            // ticks before it may hide again after coming out
    private static final int SEARCH_TRIES = 48;
    private static final int SEARCH_RADIUS = 14;        // path finding only reaches about 16 blocks
    private static final int FULL_CHECKS = 6;           // best candidates that get the full-body visibility test
    private static final int PATH_VALIDATIONS = 3;      // best hidden candidates that get a (costly) path check
    private static final int SEARCH_RETRY = 40;         // ticks to wait before searching again after finding nothing
    private static final double SPEED = 1.3;

    // Rays are sent to these points on the golem (about 1.4 wide, 2.7 tall); all of them must be blocked.
    private static final double[] HEIGHTS = {0.1, 0.9, 1.7, 2.6};
    private static final double[][] OFFSETS = {{0.0, 0.0}, {0.65, 0.65}, {0.65, -0.65}, {-0.65, 0.65}, {-0.65, -0.65}};

    private record Candidate(BlockPos feet, Vec3 position, double score) {
    }

    private final IronGolem golem;
    private Player player;
    private int startDelay;
    private int armedStreak;
    private int runDelay;
    private int searchWait;
    private int unarmed;
    private long readyAt;
    private Vec3 destination;
    private boolean done;

    public TakeCoverGoal(IronGolem golem) {
        this.golem = golem;
        this.startDelay = golem.getRandom().nextInt(START_CHECK_CALLS); // stagger
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!golem.isAlive() || !FortifiedHandler.enabled(golem.level())) {
            armedStreak = 0;
            return false;
        }
        if (!(golem.getTarget() instanceof Player target) || !target.isAlive() || target.isSpectator() || target.isCreative()
                || golem.distanceToSqr(target) > MAX_PLAYER_DIST * MAX_PLAYER_DIST) {
            armedStreak = 0;
            return false;
        }
        if (golem.level().getGameTime() < readyAt) {
            return false;
        }
        if (--startDelay > 0) {
            return false;
        }
        startDelay = START_CHECK_CALLS;

        if (!isArmed(target) || golem.distanceToSqr(target) < START_DIST * START_DIST) {
            armedStreak = 0;
            return false;
        }
        if (++armedStreak < ARMED_CHECKS) {
            return false;
        }
        player = target;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // Note: golem.getTarget() is NOT required. Vanilla drops a target it cannot see after 3 seconds, which
        // is exactly what happens while the golem hides. Its anger at the player is what keeps this going.
        return !done
                && golem.isAlive()
                && FortifiedHandler.enabled(golem.level())
                && player != null
                && player.isAlive()
                && !player.isSpectator()
                && player.level() == golem.level()
                && golem.distanceToSqr(player) <= MAX_PLAYER_DIST * MAX_PLAYER_DIST * 2.25
                && golem.isAngryAt(player);
    }

    @Override
    public void start() {
        done = false;
        runDelay = 0;
        searchWait = 0;
        unarmed = 0;
        destination = null;
    }

    @Override
    public void stop() {
        golem.getNavigation().stop();
        FortifiedHandler.setParrying(golem, false);
        player = null;
        destination = null;
        armedStreak = 0;
        startDelay = START_CHECK_CALLS;
        done = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Ends the goal and hands the golem back to vanilla, aimed at the player. */
    private void release() {
        done = true;
        readyAt = golem.level().getGameTime() + COOLDOWN;
        if (player != null && player.isAlive() && golem.isAngryAt(player)) {
            golem.setTarget(player);
        }
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

        // The player got close: spring out and fight.
        if (golem.distanceToSqr(player) < AMBUSH_DIST * AMBUSH_DIST) {
            release();
            return;
        }
        // The player put the bow away for a while: come out.
        if (isArmed(player)) {
            unarmed = 0;
        } else {
            unarmed += RUN_CHECK_TICKS;
            if (unarmed >= UNARMED_RELEASE) {
                release();
                return;
            }
        }

        // Completely out of sight: stay put.
        if (isHidden(golem.position())) {
            golem.getNavigation().stop();
            destination = null;
            FortifiedHandler.setParrying(golem, false);
            return;
        }

        // Still on its way to a spot that is good: keep going.
        if (destination != null && !golem.getNavigation().isDone() && isHidden(destination)) {
            FortifiedHandler.setParrying(golem, false);
            return;
        }
        destination = null;

        if (searchWait > 0) {
            searchWait -= RUN_CHECK_TICKS;
            return;
        }

        Vec3 cover = findCover();
        if (cover != null && golem.getNavigation().moveTo(cover.x, cover.y, cover.z, SPEED)) {
            destination = cover;
            FortifiedHandler.setParrying(golem, false);
            return;
        }

        // Nothing to hide behind: stand and parry arrows. Look again for cover in a moment.
        golem.getNavigation().stop();
        FortifiedHandler.setParrying(golem, true);
        searchWait = SEARCH_RETRY;
    }

    private static boolean isArmed(Player p) {
        return isRanged(p.getMainHandItem()) || isRanged(p.getOffhandItem());
    }

    private static boolean isRanged(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem;
    }

    /** Far enough from the player sideways, and no part of a golem standing at feet can be seen by the player. */
    private boolean isHidden(Vec3 feet) {
        if (horizontalDistance(feet, player) < MIN_COVER_DIST) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        // Cheap rejects first: head and belly.
        if (!blocked(eye, feet.add(0.0, 0.9, 0.0)) || !blocked(eye, feet.add(0.0, 2.6, 0.0))) {
            return false;
        }
        for (double[] offset : OFFSETS) {
            for (double height : HEIGHTS) {
                if (!blocked(eye, feet.add(offset[0], height, offset[1]))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Is there something the player cannot see through (glass does not count) between the two points? */
    private boolean blocked(Vec3 from, Vec3 to) {
        return golem.level()
                .clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                .getType() != HitResult.Type.MISS;
    }

    /** A spot the golem can walk to where it is completely hidden from the player, or null. */
    private Vec3 findCover() {
        Level level = golem.level();
        RandomSource random = golem.getRandom();
        BlockPos origin = golem.blockPosition();
        Vec3 eye = player.getEyePosition();

        List<Candidate> found = new ArrayList<>();
        for (int i = 0; i < SEARCH_TRIES; i++) {
            int x = origin.getX() + random.nextIntBetweenInclusive(-SEARCH_RADIUS, SEARCH_RADIUS);
            int z = origin.getZ() + random.nextIntBetweenInclusive(-SEARCH_RADIUS, SEARCH_RADIUS);
            BlockPos feet = groundAt(level, x, origin.getY(), z);
            if (feet == null) {
                continue;
            }
            Vec3 position = Vec3.atBottomCenterOf(feet);
            if (horizontalDistance(position, player) < MIN_COVER_DIST) {
                continue;
            }
            if (!blocked(eye, position.add(0.0, 0.9, 0.0)) || !blocked(eye, position.add(0.0, 2.6, 0.0))) {
                continue;
            }
            // Close to the golem is quick shelter; a bit further from the player is better.
            double score = -position.distanceTo(golem.position()) + 0.15 * horizontalDistance(position, player);
            found.add(new Candidate(feet, position, score));
        }
        found.sort((a, b) -> Double.compare(b.score(), a.score()));

        int fullChecks = 0;
        int pathChecks = 0;
        for (Candidate candidate : found) {
            if (fullChecks++ >= FULL_CHECKS) {
                break;
            }
            if (!isHidden(candidate.position())) {
                continue;
            }
            if (pathChecks++ >= PATH_VALIDATIONS) {
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
