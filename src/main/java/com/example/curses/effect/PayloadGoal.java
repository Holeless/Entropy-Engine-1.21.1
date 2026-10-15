package com.example.curses.effect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.example.curses.CursesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PICKUP phase of Payload: a phantom that is hunting a player looks for a creeper near that player, dives to it
 * and grabs it. The moment the creeper is mounted this goal ends and {@link PayloadHandler#beginCarry} takes
 * over, because a goal cannot carry the creeper: vanilla switches off MOVE goals while a mob rides the phantom.
 *
 * Creepers are searched for around the PLAYER, not the phantom: a hunting phantom circles 20-40 blocks above
 * its target, so a search centred on the phantom almost never finds anything on the ground.
 *
 * <b>Siege mode:</b> a phantom that cannot see a nearby player (they are under a roof, see
 * {@link PayloadHandler#siegeVictim}) no longer has a target of its own, because vanilla drops targets it cannot
 * see. It then works on that player anyway, and it does not give up:
 * <ol>
 *   <li><b>Search.</b> It always goes for a creeper (no random chance) and looks far: around the player and
 *       around itself, {@link #SEEK_H} blocks sideways and {@link #SEEK_V} blocks up and down (so creepers on the
 *       ground far below a sky base are found). The nearest creeper in the open is taken.</li>
 *   <li><b>Seek.</b> If there is none, it does not go back to circling. It flies out over the area in a wide loop
 *       around the player (a new waypoint every few seconds), searching again every second, until it spots a
 *       creeper, for up to a minute before it takes a breather and starts again.</li>
 *   <li><b>Return.</b> Once it has a creeper, the carry phase flies it back and drops it on the roof, so the
 *       lit creeper blows the roof open. Then it goes straight back to step 1 for the next one.</li>
 * </ol>
 *
 * Phantoms steer by {@code moveTargetPoint} (made public by the access transformer), not by the usual wanted
 * position, so that is what this goal sets every tick.
 */
public class PayloadGoal extends Goal {
    private static final double MAX_PLAYER_DIST = 64.0; // only when its target is this close
    private static final double CREEPER_RANGE_H = 24.0; // creepers this far (sideways) from the player qualify
    private static final double CREEPER_RANGE_V = 16.0; // ... and this far up/down
    private static final double SEEK_H = 72.0;          // siege: search this far sideways (around player and phantom)
    private static final double SEEK_V = 200.0;         // siege: ... and this far up/down (sky bases: creepers wait far below)
    private static final double MIN_PLAYER_GAP_SQR = 7.0 * 7.0; // skip creepers about to blow up on the player anyway
    private static final double SIEGE_MIN_GAP_SQR = 3.0 * 3.0;
    private static final float CHANCE = 0.5F;           // per search
    private static final int SEARCH_INTERVAL = 20;      // ticks between searches
    private static final int COOLDOWN = 400;            // ticks before the same phantom does it again
    private static final int SIEGE_COOLDOWN = 40;
    private static final int SEEK_COOLDOWN = 40;
    private static final int PICKUP_TIMEOUT = 300;
    private static final int SIEGE_PICKUP_TIMEOUT = 700; // the creeper may be far away
    private static final int SEEK_TIMEOUT = 1200;        // a minute of searching before a breather
    private static final double GRAB_DIST_SQR = 3.0 * 3.0;

    private static final double WAYPOINT_MIN = 28.0;    // waypoints lie this far from the player (kept inside
    private static final double WAYPOINT_MAX = 52.0;    // the area the server keeps loaded and ticking)
    private static final double WAYPOINT_REACHED_SQR = 6.0 * 6.0;
    private static final int WAYPOINT_MAX_AGE = 160;

    private final Phantom phantom;
    private Creeper creeper;
    private Player victim;
    private boolean siege;
    private boolean seeking;
    private Vec3 waypoint;
    private int waypointAge;
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

        // Its own target if it has one; otherwise a player it cannot see (sealed in) counts as the target.
        LivingEntity target = phantom.getTarget();
        Player sealed = PayloadHandler.siegeVictim(phantom);
        Player player = target instanceof Player p ? p : sealed;
        boolean besieging = player != null && player == sealed;
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (!besieging && phantom.distanceToSqr(player) > MAX_PLAYER_DIST * MAX_PLAYER_DIST) {
            return false;
        }
        if (!besieging && phantom.getRandom().nextFloat() >= CHANCE) {
            return false;
        }

        List<Creeper> found = findCreepers(player, besieging);
        if (found.isEmpty()) {
            if (!besieging) {
                return false;
            }
            // Sealed-in player and no creeper in reach: go and look for one instead of giving up.
            creeper = null;
            victim = player;
            siege = true;
            seeking = true;
            CursesMod.LOGGER.debug("Payload: phantom {} found no creeper, searching for one", phantom.getId());
            return true;
        }

        creeper = nearestTo(phantom, found);
        victim = player;
        siege = besieging;
        seeking = false;
        CursesMod.LOGGER.debug("Payload: phantom {} going for creeper {} ({} creepers found{})",
                phantom.getId(), creeper.getId(), found.size(), besieging ? ", player sealed in" : "");
        return true;
    }

    /** Creepers a phantom could grab for this player, or (siege) anywhere in the wide search area. */
    private List<Creeper> findCreepers(Player player, boolean besieging) {
        Level level = phantom.level();
        double rangeH = besieging ? SEEK_H : CREEPER_RANGE_H;
        double rangeV = besieging ? SEEK_V : CREEPER_RANGE_V;
        double minGapSqr = besieging ? SIEGE_MIN_GAP_SQR : MIN_PLAYER_GAP_SQR;
        java.util.function.Predicate<Creeper> usable = c -> c.isAlive()
                && !c.isPassenger()
                && !c.isVehicle()
                && !c.isIgnited()
                && !c.getPersistentData().getBoolean(PayloadHandler.CARRIED)
                && c.distanceToSqr(player) >= minGapSqr
                && (level.canSeeSky(c.blockPosition()) || phantom.hasLineOfSight(c));

        List<Creeper> found = new ArrayList<>(
                level.getEntitiesOfClass(Creeper.class, player.getBoundingBox().inflate(rangeH, rangeV, rangeH), usable));
        if (besieging) {
            // The phantom may be out on a search, so look around it too.
            AABB around = phantom.getBoundingBox().inflate(rangeH, rangeV, rangeH);
            for (Creeper c : level.getEntitiesOfClass(Creeper.class, around, usable)) {
                if (!found.contains(c)) {
                    found.add(c);
                }
            }
        }
        return found;
    }

    /** The creeper closest to the phantom: the shortest dive. */
    private static Creeper nearestTo(Phantom phantom, List<Creeper> creepers) {
        Creeper nearest = null;
        double best = Double.MAX_VALUE;
        for (Creeper c : creepers) {
            double d = phantom.distanceToSqr(c);
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        return nearest;
    }

    @Override
    public boolean canContinueToUse() {
        if (done || !phantom.isAlive() || !PayloadHandler.enabled(phantom.level())) {
            return false;
        }
        if (victim == null || !victim.isAlive() || victim.isSpectator() || victim.level() != phantom.level()) {
            return false;
        }
        if (seeking) {
            // Searching only makes sense while the player is still sealed in (the phantom remembers that).
            return ticks < SEEK_TIMEOUT && PayloadHandler.siegeVictim(phantom) == victim;
        }
        if (creeper == null || !creeper.isAlive() || creeper.isPassenger()) {
            return false;
        }
        return ticks < (siege ? SIEGE_PICKUP_TIMEOUT : PICKUP_TIMEOUT);
    }

    @Override
    public void start() {
        ticks = 0;
        done = false;
        waypoint = null;
        waypointAge = 0;
    }

    @Override
    public void stop() {
        long cooldown = seeking ? SEEK_COOLDOWN : siege ? SIEGE_COOLDOWN : COOLDOWN;
        readyAt = phantom.level().getGameTime() + cooldown;
        creeper = null;
        victim = null;
        siege = false;
        seeking = false;
        waypoint = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        if (victim == null) {
            return;
        }
        if (seeking) {
            tickSeek();
            if (seeking) {
                return;
            }
        }
        if (creeper == null) {
            return;
        }

        // Hover just above the creeper's head.
        phantom.moveTargetPoint = creeper.position().add(0.0, creeper.getBbHeight() + 0.4, 0.0);

        if (phantom.distanceToSqr(creeper) < GRAB_DIST_SQR) {
            if (creeper.startRiding(phantom, true)) {
                PayloadHandler.beginCarry(phantom, creeper, victim, siege); // carrying continues outside the goal system
            } else {
                CursesMod.LOGGER.debug("Payload: phantom {} could not mount creeper {}", phantom.getId(), creeper.getId());
            }
            done = true;
        }
    }

    /** Flies a loop around the player, looking for a creeper once a second. Switches to pickup when it finds one. */
    private void tickSeek() {
        if (ticks % SEARCH_INTERVAL == 0) {
            List<Creeper> found = findCreepers(victim, true);
            if (!found.isEmpty()) {
                creeper = nearestTo(phantom, found);
                seeking = false;
                ticks = 0;
                CursesMod.LOGGER.debug("Payload: phantom {} spotted creeper {} while searching", phantom.getId(), creeper.getId());
                return;
            }
        }

        waypointAge++;
        if (waypoint == null || waypointAge > WAYPOINT_MAX_AGE || phantom.position().distanceToSqr(waypoint) < WAYPOINT_REACHED_SQR) {
            waypoint = pickWaypoint();
            waypointAge = 0;
        }
        phantom.moveTargetPoint = waypoint;
    }

    /** A point high above the ground 28-52 blocks from the player, in a loaded chunk. */
    private Vec3 pickWaypoint() {
        Level level = phantom.level();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = phantom.getRandom().nextDouble() * Math.PI * 2.0;
            double radius = WAYPOINT_MIN + phantom.getRandom().nextDouble() * (WAYPOINT_MAX - WAYPOINT_MIN);
            double x = victim.getX() + Math.cos(angle) * radius;
            double z = victim.getZ() + Math.sin(angle) * radius;
            double y = Math.min(victim.getY() + 22.0 + phantom.getRandom().nextInt(14), level.getMaxBuildHeight() - 10);
            if (level.hasChunkAt(BlockPos.containing(x, y, z))) {
                return new Vec3(x, y, z);
            }
        }
        return new Vec3(victim.getX(), Math.min(victim.getY() + 25.0, level.getMaxBuildHeight() - 10), victim.getZ());
    }
}
