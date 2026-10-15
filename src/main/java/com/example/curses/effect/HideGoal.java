package com.example.curses.effect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Kiting, cover part: right after a ranged mob fires, it runs to a spot where you cannot see it, waits there for
 * 1.5 to 3 seconds, then comes back out for the next shot.
 *
 * <p><b>Cover</b> is a spot the mob can walk to (up to about 12 blocks away), at least 5 blocks from the player,
 * where the player's eyes have no clear line to the mob's body or head. Something in the way counts if it is a
 * block (glass does not count) <i>or another mob</i> (its hitbox is checked against the line), so a skeleton will
 * duck behind a wall, a tree, or a zombie. If the player moves and the spot stops being hidden, it moves to a new
 * one. If there is no cover at all, it just carries on (the kite goal still backs it away).</p>
 *
 * <p>One hide per shot. It has priority 1 and the MOVE flag, so while it runs, the vanilla attack goal is paused
 * (the mob puts its bow down), and when it ends the attack goal starts again from scratch: the mob draws, steps
 * out into view and fires. It does nothing while the curse is off.</p>
 */
public class HideGoal extends Goal {
    private static final double MAX_DIST = 40.0;         // only hide from a player this close
    private static final double MIN_DIST = 4.0;          // closer than this, hiding is pointless
    private static final double MIN_COVER_DIST = 5.0;    // cover must be this far from the player sideways
    private static final int SHOT_WINDOW = 6;            // ticks after a shot in which the hide can still start
    private static final int HIDE_MIN = 30;              // ticks to stay hidden...
    private static final int HIDE_RANDOM = 31;           // ... plus up to this many more
    private static final int COOLDOWN = 20;              // ticks after a hide before the next can start
    private static final int RECHECK = 10;               // ticks between "is this still cover?" checks
    private static final int SEARCH_TRIES = 40;
    private static final int SEARCH_RADIUS = 12;
    private static final int PATH_CHECKS = 3;            // best cover spots that get a (costly) path check
    private static final double BLOCKER_RADIUS = 20.0;   // other mobs this close can be hidden behind
    private static final double SPEED = 1.1;

    /** Rays go from the player's eyes to these heights above the mob's feet (body and head); both must be blocked. */
    private static final double[] HEIGHTS = {0.9, 1.6};

    private record Candidate(BlockPos feet, Vec3 position, double score) {
    }

    private final Mob mob;
    private Player player;
    private Vec3 destination;
    private long hideUntil;
    private long readyAt;
    private long handledShot;
    private int recheck;
    private boolean done;

    public HideGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive() || mob.isPassenger() || !KitingHandler.enabled(mob.level()) || !KitingHandler.isArmed(mob)) {
            return false;
        }
        Long shot = KitingHandler.LAST_SHOT.get(mob);
        long now = mob.level().getGameTime();
        if (shot == null || shot <= handledShot || now - shot > SHOT_WINDOW || now < readyAt) {
            return false;
        }
        if (!(mob.getTarget() instanceof Player target) || !valid(target)) {
            return false;
        }
        double distance = mob.distanceTo(target);
        if (distance < MIN_DIST || distance > MAX_DIST) {
            return false;
        }

        handledShot = shot; // one hide per shot, whether or not cover is found
        Vec3 cover = findCover(target);
        if (cover == null) {
            player = null;
            return false;
        }
        player = target;
        destination = cover;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !done
                && mob.isAlive()
                && player != null
                && valid(player)
                && player.level() == mob.level()
                && KitingHandler.enabled(mob.level())
                && mob.level().getGameTime() < hideUntil;
    }

    @Override
    public void start() {
        done = false;
        recheck = RECHECK;
        hideUntil = mob.level().getGameTime() + HIDE_MIN + mob.getRandom().nextInt(HIDE_RANDOM);
        KitingHandler.HIDING.add(mob);
        if (destination != null) {
            mob.getNavigation().moveTo(destination.x, destination.y, destination.z, SPEED);
        }
    }

    @Override
    public void stop() {
        KitingHandler.HIDING.remove(mob);
        mob.getNavigation().stop();
        readyAt = mob.level().getGameTime() + COOLDOWN;
        player = null;
        destination = null;
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
        mob.getLookControl().setLookAt(player, 30.0F, 30.0F);
        if (--recheck > 0) {
            return;
        }
        recheck = RECHECK;

        Vec3 eye = player.getEyePosition();
        List<LivingEntity> blockers = blockers();
        if (hiddenAt(mob.position(), eye, blockers)) {
            // Out of sight: stay put until the time is up.
            mob.getNavigation().stop();
            destination = null;
            return;
        }
        if (destination != null && !mob.getNavigation().isDone() && hiddenAt(destination, eye, blockers)) {
            return; // still on its way to a good spot
        }
        Vec3 cover = findCover(player);
        if (cover != null && mob.getNavigation().moveTo(cover.x, cover.y, cover.z, SPEED)) {
            destination = cover;
        } else {
            done = true; // nowhere to hide: back to fighting
        }
    }

    private static boolean valid(Player p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative();
    }

    /** Other mobs near this one that a mob could stand behind. Small things (bats, silverfish) do not count. */
    private List<LivingEntity> blockers() {
        return mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(BLOCKER_RADIUS),
                e -> e != mob && e != player && !(e instanceof Player) && e.isAlive()
                        && e.getBbWidth() >= 0.5F && e.getBbHeight() >= 1.0F);
    }

    /** Can the player's eyes not see any part of a mob standing at feet? */
    private boolean hiddenAt(Vec3 feet, Vec3 eye, List<LivingEntity> blockers) {
        for (double height : HEIGHTS) {
            if (!covered(eye, feet.add(0.0, height, 0.0), blockers)) {
                return false;
            }
        }
        return true;
    }

    /** Is there a block (glass does not count) or another mob between the player's eye and this point? */
    private boolean covered(Vec3 eye, Vec3 point, List<LivingEntity> blockers) {
        if (mob.level().clip(new ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                .getType() != HitResult.Type.MISS) {
            return true;
        }
        for (LivingEntity blocker : blockers) {
            if (blocker.getBoundingBox().clip(eye, point).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** A spot the mob can walk to that is out of the player's sight, or null. Nearest to the mob first. */
    private Vec3 findCover(Player target) {
        Level level = mob.level();
        RandomSource random = mob.getRandom();
        BlockPos origin = mob.blockPosition();
        Vec3 eye = target.getEyePosition();
        int height = Mth.ceil(mob.getBbHeight());
        player = target; // covered() ray-casts as the player; set early because canUse() calls this first
        List<LivingEntity> blockers = blockers();

        List<Candidate> found = new ArrayList<>();
        for (int i = 0; i < SEARCH_TRIES; i++) {
            int x = origin.getX() + random.nextIntBetweenInclusive(-SEARCH_RADIUS, SEARCH_RADIUS);
            int z = origin.getZ() + random.nextIntBetweenInclusive(-SEARCH_RADIUS, SEARCH_RADIUS);
            BlockPos feet = groundAt(level, x, origin.getY(), z, height);
            if (feet == null) {
                continue;
            }
            Vec3 position = Vec3.atBottomCenterOf(feet);
            if (horizontalDistance(position, target) < MIN_COVER_DIST || !hiddenAt(position, eye, blockers)) {
                continue;
            }
            found.add(new Candidate(feet, position, -position.distanceTo(mob.position())));
        }
        found.sort((a, b) -> Double.compare(b.score(), a.score()));

        int pathChecks = 0;
        for (Candidate candidate : found) {
            if (pathChecks++ >= PATH_CHECKS) {
                break;
            }
            Path path = mob.getNavigation().createPath(candidate.feet(), 0);
            if (path != null && path.canReach()) {
                return candidate.position();
            }
        }
        return null;
    }

    /** Standable spot in this column near the given height, or null. */
    private static BlockPos groundAt(Level level, int x, int y, int z, int height) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.hasChunkAt(pos)) {
                return null;
            }
            if (KitingHandler.standable(level, pos, height)) {
                return pos;
            }
        }
        return null;
    }

    private static double horizontalDistance(Vec3 from, Player to) {
        double dx = from.x - to.getX();
        double dz = from.z - to.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
