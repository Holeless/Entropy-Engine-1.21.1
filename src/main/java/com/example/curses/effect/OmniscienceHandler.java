package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Omniscience: hostile mobs always know where you are, however far, as long as you are within render distance
 * (the server's view distance).
 *
 * <p>Vanilla mobs only notice a player inside their {@code follow_range} and with a line of sight. Here a hostile
 * mob that has nothing to attack simply takes the nearest player in range as its target, sight or no sight, and
 * vanilla's own attack goals do the rest (walking, shooting, swelling). It is done by handing out the
 * <i>target</i>, not by inflating {@code follow_range}: a huge follow range would make every path search copy a
 * huge region of the world, which is exactly what causes lag.</p>
 *
 * <p>Vanilla's target goals drop a target they can no longer see or that is out of their normal range. That is
 * undone here ({@link #onChangeTarget}), so a mob keeps hunting a player it has been given.</p>
 *
 * <p><b>Keeping the server fast</b> (a slow server is what makes item pickups lag, attacks arrive late, and broken
 * blocks reappear):</p>
 * <ul>
 *   <li>Each mob looks once a second, staggered by its id so they never all look on the same tick, and a mob that
 *       already has a target does nothing at all.</li>
 *   <li>At most {@link #BUDGET} mobs get a new target per server tick, so a crowd waking up is spread over a few
 *       ticks instead of all pathfinding at once.</li>
 *   <li>The cost of a tick is measured. When the smoothed tick time goes above {@link #SLOW_MS} the curse stops
 *       handing out new targets until it has recovered below {@link #RECOVER_MS}; mobs that already hunt carry
 *       on, so it eases off exactly when the server is struggling.</li>
 *   <li>The curse flag and the range are read once a second, not per mob.</li>
 * </ul>
 *
 * <p>Left out on purpose: neutral mobs (endermen, zombified piglins) and brain-driven mobs (piglins, the Warden),
 * which do not use plain targets, and spiders in daylight, which vanilla keeps passive. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class OmniscienceHandler {
    private static final int CHECK_INTERVAL = 20;       // ticks between looks per mob
    private static final int BUDGET = 6;                // new targets handed out per server tick, at most
    private static final double SLOW_MS = 42.0;         // smoothed tick time above which the curse backs off
    private static final double RECOVER_MS = 34.0;      // ... and below which it resumes
    private static final double SMOOTHING = 0.05;       // about the last second of ticks
    private static final double MIN_RANGE = 64.0;

    // Server thread only.
    private static volatile boolean active;
    private static volatile double rangeSqr = MIN_RANGE * MIN_RANGE;
    private static boolean throttled;
    private static double smoothedMs;
    private static long tickStart;
    private static int budget;

    private OmniscienceHandler() {
    }

    // ---------------------------------------------------------------------------------------------------
    // Server tick: cached settings, per-tick budget, and the lag guard
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        tickStart = System.nanoTime();
        budget = BUDGET;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 0) {
            active = CurseData.isActive(server, Curses.OMNISCIENCE);
            double range = Math.max(MIN_RANGE, server.getPlayerList().getViewDistance() * 16.0);
            rangeSqr = range * range;
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        double ms = (System.nanoTime() - tickStart) / 1.0E6;
        smoothedMs += (ms - smoothedMs) * SMOOTHING;
        if (!throttled && smoothedMs > SLOW_MS) {
            throttled = true;
            if (active) {
                CursesMod.LOGGER.debug("Omniscience: server is slow ({} ms/tick), not handing out new targets", (int) smoothedMs);
            }
        } else if (throttled && smoothedMs < RECOVER_MS) {
            throttled = false;
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        active = false;
        throttled = false;
        smoothedMs = 0.0;
        budget = 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Finding prey
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!active || throttled || budget <= 0 || !(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        if ((mob.tickCount + mob.getId()) % CHECK_INTERVAL != 0 || !mob.isAlive()) {
            return;
        }
        LivingEntity current = mob.getTarget();
        boolean staleSomeone = current instanceof Player held && !isPrey(mob, held);
        if (current != null && current.isAlive() && !staleSomeone) {
            return; // already hunting something
        }
        if (!isEligible(mob)) {
            return;
        }

        Player prey = nearestPrey(mob, (ServerLevel) mob.level());
        if (prey != null) {
            mob.setTarget(prey);
            budget--;
        }
    }

    /**
     * Keeps a given target: when vanilla's target goal lets go of a player (out of its range, out of sight for
     * three seconds), the mob stays on that player as long as they are still valid prey in range.
     */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!active || event.getNewAboutToBeSetTarget() != null || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (mob.level().isClientSide() || !isEligible(mob)) {
            return;
        }
        if (mob.getTarget() instanceof Player current && isPrey(mob, current)) {
            event.setNewAboutToBeSetTarget(current);
        }
    }

    private static boolean isEligible(Mob mob) {
        if (!(mob instanceof Enemy) || mob instanceof NeutralMob || mob instanceof AbstractPiglin || mob instanceof Warden) {
            return false;
        }
        if (mob.isPassenger()) {
            return false;
        }
        // Spiders are passive in daylight in vanilla, and drop their target on their own.
        return !(mob instanceof Spider && mob.getLightLevelDependentMagicValue() >= 0.5F);
    }

    private static boolean isPrey(Mob mob, Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative()
                && player.level() == mob.level()
                && mob.distanceToSqr(player) <= rangeSqr
                && mob.canAttack(player);
    }

    private static Player nearestPrey(Mob mob, ServerLevel level) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : level.players()) {
            if (!isPrey(mob, player)) {
                continue;
            }
            double distance = mob.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }
}
