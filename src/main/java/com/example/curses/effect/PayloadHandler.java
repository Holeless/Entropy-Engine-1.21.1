package com.example.curses.effect;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Payload: phantoms can pick up creepers and drop them onto you.
 *
 * Two phases:
 *  1. PICKUP is a normal AI goal, {@link PayloadGoal}: the phantom dives to a creeper near its target and
 *     grabs it.
 *  2. CARRY is NOT a goal, it is driven from {@link #onPhantomTick}. Vanilla switches off a mob's MOVE goals
 *     while a mob is riding it, which used to stop the goal the moment the creeper was picked up and dump the
 *     creeper instantly. A per-tick handler does not care about goal flags.
 *
 * While carrying, the phantom is steered directly (moveTargetPoint plus a direct velocity nudge, so it works
 * even if the phantom's move control has been swapped out) to a point above the player. When it is overhead
 * and high enough the creeper is released with its fuse already lit, so it explodes on or next to the player.
 * A carried creeper takes no fall damage.
 *
 * <b>Siege:</b> a phantom that has been near a player for a few seconds without being able to see them (they are
 * under a roof) is "besieging" that player, see {@link #siegeVictim}. It does not need a target for that, since
 * vanilla drops targets a phantom cannot see. A besieging phantom always goes for a creeper, searches wider, and
 * carries it to just above the roof (see {@link #targetY}) so the lit creeper lands on the roof and blows it open.
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class PayloadHandler {
    /** Set on a creeper when a phantom picks it up. */
    static final String CARRIED = "curses_payload";
    /** Game time at which the creeper stopped being carried. */
    static final String FREED = "curses_payload_freed";

    private static final int LAND_DELAY = 3;      // ticks after release before a landing counts
    private static final int GIVE_UP = 600;       // ticks after release before the tag is forgotten
    private static final double IGNITE_RANGE = 24.0;

    private static final double DROP_HEIGHT = 10.0;     // max height above the player to drop from
    private static final double MIN_CAVE_HEIGHT = 3.0;  // lowest drop height when there is a ceiling
    private static final double HEIGHT_TOLERANCE = 1.5; // counts as "high enough" within this of the target
    private static final double DROP_RADIUS_SQR = 3.5 * 3.5;
    private static final double STALL_RADIUS_SQR = 8.0 * 8.0;
    private static final int STALL_TICKS = 160;         // carrying this long and close: just drop
    private static final int CARRY_TIMEOUT = 500;
    private static final double CARRY_SPEED = 0.45;     // blocks per tick, about 9 blocks/second

    private static final double SIEGE_RANGE = 40.0;     // a player this close that the phantom cannot see...
    private static final int SIEGE_CHECK = 10;          // ... checked this often ...
    private static final int SIEGE_TICKS = 60;          // ... for this long, means the player is sealed in
    private static final double ROOF_CLEARANCE = 3.0;   // siege drops start this far above the highest block
    private static final double ROOF_MAX_HEIGHT = 16.0; // ... if that is no more than this above the player

    private static final class Carry {
        final Creeper creeper;
        final Player victim;
        final boolean siege;
        int ticks;

        Carry(Creeper creeper, Player victim, boolean siege) {
            this.creeper = creeper;
            this.victim = victim;
            this.siege = siege;
        }
    }

    /** How long a phantom has been near a player it cannot see. */
    private static final class Blocked {
        Player player;
        int ticks;
    }

    // Server thread only.
    private static final Map<Phantom, Carry> CARRYING = new WeakHashMap<>();
    private static final Map<Phantom, Blocked> BLOCKED = new WeakHashMap<>();

    private PayloadHandler() {
    }

    /** Is the Payload curse active in the world this level belongs to? */
    static boolean enabled(Level level) {
        return level instanceof ServerLevel serverLevel && CurseData.isActive(serverLevel.getServer(), Curses.PAYLOAD);
    }

    /**
     * The player this phantom is besieging: close by, but out of its sight for a few seconds (a roof or walls
     * in the way), or null.
     */
    static Player siegeVictim(Phantom phantom) {
        Blocked blocked = BLOCKED.get(phantom);
        if (blocked == null || blocked.ticks < SIEGE_TICKS || blocked.player == null) {
            return null;
        }
        Player player = blocked.player;
        return player.isAlive() && !player.isSpectator() && player.level() == phantom.level() ? player : null;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        for (WrappedGoal wrapped : phantom.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof PayloadGoal) {
                return;
            }
        }
        // Priority 0 beats the phantom's own movement goals, which share the MOVE flag.
        // The goal does nothing while the curse is off, so it is added to every phantom.
        phantom.goalSelector.addGoal(0, new PayloadGoal(phantom));
    }

    /** Called by {@link PayloadGoal} the moment the creeper is mounted. */
    static void beginCarry(Phantom phantom, Creeper creeper, Player victim, boolean siege) {
        creeper.getPersistentData().putBoolean(CARRIED, true);
        CARRYING.put(phantom, new Carry(creeper, victim, siege));
        CursesMod.LOGGER.debug("Payload: phantom {} is carrying creeper {}{}", phantom.getId(), creeper.getId(),
                siege ? " to a sealed-in player" : "");
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CARRYING.clear();
        BLOCKED.clear();
    }

    @SubscribeEvent
    public static void onPhantomTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Phantom phantom) || phantom.level().isClientSide()) {
            return;
        }
        Carry carry = CARRYING.get(phantom);
        if (carry != null) {
            tickCarry(phantom, carry);
        } else {
            updateBlocked(phantom);
        }
    }

    /** Tracks how long the phantom has been near a player without seeing them. */
    private static void updateBlocked(Phantom phantom) {
        if (phantom.tickCount % SIEGE_CHECK != 0) {
            return;
        }
        if (!enabled(phantom.level())) {
            BLOCKED.remove(phantom);
            return;
        }
        Player near = phantom.level().getNearestPlayer(phantom, SIEGE_RANGE);
        if (near == null || near.isCreative() || near.isSpectator() || phantom.hasLineOfSight(near)) {
            BLOCKED.remove(phantom);
            return;
        }
        Blocked blocked = BLOCKED.computeIfAbsent(phantom, p -> new Blocked());
        blocked.player = near;
        blocked.ticks += SIEGE_CHECK;
    }

    private static void tickCarry(Phantom phantom, Carry c) {
        c.ticks++;
        Creeper creeper = c.creeper;
        Player victim = c.victim;

        String problem = null;
        if (!enabled(phantom.level())) {
            problem = "curse lifted";
        } else if (!phantom.isAlive()) {
            problem = "phantom died";
        } else if (!creeper.isAlive()) {
            problem = "creeper died";
        } else if (creeper.getVehicle() != phantom) {
            problem = "creeper is no longer riding";
        } else if (!victim.isAlive() || victim.level() != phantom.level()) {
            problem = "player gone";
        } else if (c.ticks > CARRY_TIMEOUT) {
            problem = "carry timed out";
        }
        if (problem != null) {
            CursesMod.LOGGER.debug("Payload: phantom {} carry ended early: {} (tick {})",
                    phantom.getId(), problem, c.ticks);
            finish(phantom, c, false);
            return;
        }

        // Keep vanilla's phantom goals from steering while we carry. The game re-enables this flag every few
        // ticks on its own, so it is switched off again every tick.
        phantom.goalSelector.disableControlFlag(Goal.Flag.MOVE);

        double targetY = targetY(phantom.level(), victim, c.siege);
        Vec3 target = new Vec3(victim.getX(), targetY, victim.getZ());
        phantom.moveTargetPoint = target;
        steer(phantom, target);

        double dx = phantom.getX() - victim.getX();
        double dz = phantom.getZ() - victim.getZ();
        double horizSqr = dx * dx + dz * dz;
        double height = phantom.getY() - victim.getY();
        boolean overhead = horizSqr < DROP_RADIUS_SQR;
        boolean high = phantom.getY() >= targetY - HEIGHT_TOLERANCE;
        boolean stalled = c.ticks > STALL_TICKS && horizSqr < STALL_RADIUS_SQR;
        if ((overhead && high) || stalled) {
            CursesMod.LOGGER.debug("Payload: phantom {} dropping creeper {} from {} blocks up (horizontal {}, tick {})",
                    phantom.getId(), creeper.getId(),
                    String.format("%.1f", height), String.format("%.1f", Math.sqrt(horizSqr)), c.ticks);
            finish(phantom, c, true);
        }
    }

    /** Ends the carry and lets the creeper go. {@code ignite}: light its fuse so it blows up when it lands. */
    private static void finish(Phantom phantom, Carry c, boolean ignite) {
        CARRYING.remove(phantom);
        phantom.goalSelector.enableControlFlag(Goal.Flag.MOVE);

        Creeper creeper = c.creeper;
        if (creeper.getVehicle() == phantom) {
            creeper.stopRiding();
        }
        if (creeper.isAlive() && c.victim.isAlive() && c.victim.level() == creeper.level()) {
            creeper.setTarget(c.victim);
            if (ignite) {
                creeper.ignite();
            }
        }
    }

    /** Pulls the phantom toward {@code target} and turns it to face the way it is going. */
    private static void steer(Phantom phantom, Vec3 target) {
        Vec3 to = target.subtract(phantom.position());
        double dist = to.length();
        if (dist < 1.0E-3) {
            return;
        }

        Vec3 want = dist > CARRY_SPEED ? to.scale(CARRY_SPEED / dist) : to;
        Vec3 now = phantom.getDeltaMovement();
        phantom.setDeltaMovement(now.add(want.subtract(now).scale(0.25)));

        if (to.x * to.x + to.z * to.z > 0.25) {
            float yaw = (float) (Mth.atan2(to.z, to.x) * (180.0 / Math.PI)) - 90.0F;
            phantom.setYRot(yaw);
            phantom.yBodyRot = yaw;
            phantom.yHeadRot = yaw;
        }
    }

    /**
     * The height (world Y) to drop from. Normally up to DROP_HEIGHT above the player, less if there is a
     * ceiling. For a sealed-in player: just above the highest block over them (the roof), so the creeper lands
     * on the roof and blows it open instead of hanging inside it; if that is far above (the player is deep
     * underground) the normal height is used.
     */
    private static double targetY(Level level, Player p, boolean siege) {
        double normal = p.getY() + dropHeight(level, p);
        if (!siege) {
            return normal;
        }
        double roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ()) + ROOF_CLEARANCE;
        return roof - p.getY() <= ROOF_MAX_HEIGHT ? Math.max(roof, normal) : normal;
    }

    /** How high above the player to drop from: up to DROP_HEIGHT, less if there is a ceiling. */
    private static double dropHeight(Level level, Player p) {
        BlockPos.MutableBlockPos pos = p.blockPosition().mutable();
        int free = 0;
        while (free < (int) DROP_HEIGHT
                && level.getBlockState(pos.move(0, 1, 0)).getCollisionShape(level, pos).isEmpty()) {
            free++;
        }
        return Math.max(MIN_CAVE_HEIGHT, free - 1);
    }

    /** A carried creeper would usually die from the drop, which would defeat the point. */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) {
            return;
        }
        if (creeper.getPersistentData().getBoolean(CARRIED)) {
            event.setDamageMultiplier(0.0F);
        }
    }

    @SubscribeEvent
    public static void onCreeperTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Creeper creeper) || creeper.level().isClientSide()) {
            return;
        }

        CompoundTag data = creeper.getPersistentData();
        if (!data.getBoolean(CARRIED)) {
            return;
        }

        if (creeper.isPassenger()) {
            data.remove(FREED);
            return;
        }

        long now = creeper.level().getGameTime();
        if (!data.contains(FREED)) {
            data.putLong(FREED, now);
            return;
        }

        long since = now - data.getLong(FREED);
        if (since > GIVE_UP) {
            data.remove(CARRIED);
            data.remove(FREED);
        } else if (since >= LAND_DELAY && creeper.onGround()) {
            data.remove(CARRIED);
            data.remove(FREED);
            Player near = creeper.level().getNearestPlayer(creeper, IGNITE_RANGE);
            if (near != null && !near.isSpectator()) {
                creeper.ignite(); // already lit when dropped; this covers drops that ended early
            }
        }
    }
}
