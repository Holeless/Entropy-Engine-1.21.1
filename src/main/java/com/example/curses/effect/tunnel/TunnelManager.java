package com.example.curses.effect.tunnel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Owns the shared tunnels of the Omniscience curse (one per hunted player) and keeps the whole feature cheap.
 *
 * <p><b>Lag guard.</b> Everything here is rationed:</p>
 * <ul>
 *   <li>route planning is time-sliced: all planners together get {@link #PLAN_BUDGET_NANOS} (1 ms) per tick;</li>
 *   <li>at most {@link #DIG_BUDGET} dug steps and {@link #NAV_BUDGET} new mob path searches per tick;</li>
 *   <li>the tick time is measured, and when its smoothed value goes above {@link #PAUSE_MS} everything here
 *       pauses (planning, digging, new path searches) until it is back under {@link #RESUME_MS}. The game then
 *       behaves like vanilla + the base Omniscience curse, so combat and block breaking stay responsive.</li>
 * </ul>
 *
 * <p>Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class TunnelManager {
    static final double PLAN_RANGE = 72.0;

    private static final int MAX_JOBS = 2;
    private static final long PLAN_BUDGET_NANOS = 1_000_000L;
    private static final int NAV_BUDGET = 3;
    private static final int DIG_BUDGET = 2;
    private static final double PAUSE_MS = 30.0;
    private static final double RESUME_MS = 25.0;
    private static final double SMOOTHING = 0.05;
    private static final long IDLE_TICKS = 1800L;      // a plan nobody uses for 90 s is dropped
    private static final long RETRY_TICKS = 600L;      // after a failed plan, wait before trying for that player again
    private static final long REPLAN_SPACING = 100L;
    private static final double REPLAN_DRIFT_SQR = 6.0 * 6.0; // the player moved this far from where the tunnel aims

    private static final Map<UUID, TunnelPlan> PLANS = new HashMap<>();
    private static final Map<UUID, PlanJob> JOBS = new LinkedHashMap<>();
    private static final Map<UUID, Long> RETRY_AT = new HashMap<>();

    private static volatile boolean active;
    private static boolean paused;
    private static double smoothedMs;
    private static long tickStart;
    private static int navBudget;
    private static int digBudget;

    private TunnelManager() {
    }

    // ---------------------------------------------------------------------------------------------------
    // Public (package) API
    // ---------------------------------------------------------------------------------------------------

    /** Is Omniscience active in this world? (refreshed once a second) */
    public static boolean isActive() {
        return active;
    }

    /** Active and not paused by the lag guard. */
    static boolean working() {
        return active && !paused;
    }

    static boolean takeNav() {
        if (navBudget > 0) {
            navBudget--;
            return true;
        }
        return false;
    }

    static boolean takeDig() {
        if (digBudget > 0) {
            digBudget--;
            return true;
        }
        return false;
    }

    static boolean jobRunning(UUID player) {
        return JOBS.containsKey(player);
    }

    static TunnelPlan planFor(Player player) {
        TunnelPlan plan = PLANS.get(player.getUUID());
        return plan != null && !plan.dead ? plan : null;
    }

    static TunnelPlan planPrimedBy(UUID creeper) {
        for (TunnelPlan plan : PLANS.values()) {
            if (creeper.equals(plan.primedBy)) {
                return plan;
            }
        }
        return null;
    }

    /** The player a mob is hunting, or the nearest one in range. Null for creative/spectator players. */
    static Player preyFor(Mob mob, ServerLevel level) {
        Player player = mob.getTarget() instanceof Player target ? target : level.getNearestPlayer(mob, PLAN_RANGE);
        if (player == null || !player.isAlive() || player.isCreative() || player.isSpectator()
                || player.level() != mob.level() || mob.distanceToSqr(player) > PLAN_RANGE * PLAN_RANGE) {
            return null;
        }
        return player;
    }

    /** Starts planning a tunnel to this player from anchor, if none exists and the limits allow it. */
    static boolean requestPlan(ServerLevel level, Player player, BlockPos anchor) {
        UUID id = player.getUUID();
        if (paused || PLANS.containsKey(id) || JOBS.containsKey(id) || JOBS.size() >= MAX_JOBS) {
            return false;
        }
        Long retry = RETRY_AT.get(id);
        if (retry != null && level.getGameTime() < retry) {
            return false;
        }
        if (!level.hasChunkAt(anchor)) {
            return false;
        }
        JOBS.put(id, new PlanJob(level, id, anchor, player.blockPosition(), false));
        return true;
    }

    /**
     * Opens up to {@code count} more cells at the head of the plan. Returns true if at least one was opened.
     * If something cannot be removed any more (the player placed obsidian, a chest...), the plan is re-routed.
     */
    static boolean digNext(TunnelPlan plan, Player player, int count) {
        int dug = 0;
        while (dug < count && plan.frontierPending()) {
            BlockPos prev = plan.cells.get(plan.head - 1);
            BlockPos cell = plan.cells.get(plan.head);
            if (!TunnelBlocks.excavate(plan.level, prev, cell, player.blockPosition())) {
                plan.replan = true;
                plan.nextReplan = 0L;
                break;
            }
            plan.done.set(plan.head);
            plan.head++;
            dug++;
            plan.advanceHead(); // skip cells that are already open
        }
        return dug > 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Tick: budgets, lag guard, planner jobs, housekeeping
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onTickPre(ServerTickEvent.Pre event) {
        tickStart = System.nanoTime();
        navBudget = paused ? 0 : NAV_BUDGET;
        digBudget = paused ? 0 : DIG_BUDGET;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 0) {
            active = CurseData.isActive(server, Curses.OMNISCIENCE);
        }
    }

    @SubscribeEvent
    public static void onTickPost(ServerTickEvent.Post event) {
        double ms = (System.nanoTime() - tickStart) / 1.0E6;
        smoothedMs += (ms - smoothedMs) * SMOOTHING;
        if (!paused && smoothedMs > PAUSE_MS) {
            paused = true;
        } else if (paused && smoothedMs < RESUME_MS) {
            paused = false;
        }

        if (!active) {
            if (!PLANS.isEmpty() || !JOBS.isEmpty()) {
                clearAll();
            }
            return;
        }
        if (paused) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        runJobs(server, now);
        if (server.getTickCount() % 10 == 0) {
            housekeeping(server, now);
        }
    }

    private static boolean valid(ServerPlayer player, ServerLevel level) {
        return player != null && player.isAlive() && !player.isCreative() && !player.isSpectator() && player.level() == level;
    }

    private static void runJobs(MinecraftServer server, long now) {
        if (JOBS.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + PLAN_BUDGET_NANOS;
        Iterator<Map.Entry<UUID, PlanJob>> it = JOBS.entrySet().iterator();
        while (it.hasNext() && System.nanoTime() < deadline) {
            PlanJob job = it.next().getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
            if (!valid(player, job.level)) {
                it.remove();
                continue;
            }
            if (!job.step(deadline)) {
                continue;
            }
            it.remove();
            finish(job, now);
        }
    }

    private static void finish(PlanJob job, long now) {
        UUID id = job.playerId;
        if (job.result == null) {
            RETRY_AT.put(id, now + RETRY_TICKS);
            return;
        }
        TunnelPlan plan = PLANS.get(id);
        if (job.rebase) {
            // Digging is paused while a re-route runs, so the head is still where the route starts.
            if (plan != null && !plan.dead && plan.lastOpen().equals(job.start)) {
                plan.rebase(job.result, job.goal);
                plan.lastActive = now;
            }
        } else if (plan == null) {
            PLANS.put(id, new TunnelPlan(id, job.level, job.result, job.goal, now));
        }
    }

    private static void housekeeping(MinecraftServer server, long now) {
        for (Iterator<TunnelPlan> it = PLANS.values().iterator(); it.hasNext();) {
            TunnelPlan plan = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(plan.playerId);
            if (plan.dead || !valid(player, plan.level) || now - plan.lastActive > IDLE_TICKS) {
                it.remove();
                continue;
            }
            plan.advanceHead();
            boolean drifted = player.blockPosition().distSqr(plan.goal) > REPLAN_DRIFT_SQR;
            if ((plan.replan || drifted) && now >= plan.nextReplan && !JOBS.containsKey(plan.playerId)
                    && JOBS.size() < MAX_JOBS) {
                plan.replan = false;
                plan.nextReplan = now + REPLAN_SPACING;
                JOBS.put(plan.playerId, new PlanJob(plan.level, plan.playerId, plan.lastOpen(), player.blockPosition(), true));
            }
        }
    }

    private static void clearAll() {
        PLANS.clear();
        JOBS.clear();
        RETRY_AT.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
        active = false;
        paused = false;
        smoothedMs = 0.0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Followers: every ground hostile mob gets the follow goal (it does nothing while the curse is off)
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob instanceof Enemy) || mob instanceof NeutralMob || mob instanceof AbstractPiglin || mob instanceof Warden
                || !(mob.getNavigation() instanceof GroundPathNavigation)) {
            return;
        }
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof TunnelFollowGoal) {
                return;
            }
        }
        // Priority 1 beats the vanilla melee/ranged attack goals, which would otherwise stand still at a wall.
        mob.goalSelector.addGoal(1, new TunnelFollowGoal(mob));
    }
}
