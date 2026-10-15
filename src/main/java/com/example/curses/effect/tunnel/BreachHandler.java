package com.example.curses.effect.tunnel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.effect.Siege;
import com.example.curses.registry.CursesItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Omniscience, the diggers. Three kinds of mob dig the shared tunnel to a player ({@link TunnelPlan}); the
 * rest of the hostile mobs follow it ({@link TunnelFollowGoal}).
 *
 * <ul>
 *   <li><b>Endermen</b> (underground, near a hunted player) work out the route, then dig it: one step about
 *       every {@link #ENDER_INTERVAL} ticks, stepping into each new cell. They stay neutral.</li>
 *   <li><b>Creepers</b> that are stuck or underground ask for a route. A creeper standing at the head of the
 *       tunnel lights its fuse, and when it goes off the blast opens the next {@link #BLAST_CELLS} steps of the
 *       <i>same</i> tunnel (through walls of a house, rock, cliffs). One creeper fuses per tunnel at a time, so they
 *       take turns instead of digging separate holes.</li>
 *   <li><b>Witches</b> hold back from the head ({@link TunnelFollowGoal}) and, with a clear line and at least
 *       {@link #WITCH_MIN} blocks of distance, lob dynamite at it. The dynamite opens the same stretch.</li>
 * </ul>
 *
 * <p><b>The explosions are real</b> (vanilla creeper blast, dynamite power 2: damage, knockback, crater, drops). Hostile
 * mobs are taken out of the blast's damage list, so witches cannot blow themselves up and the queue behind the
 * head is safe. One tick after the blast the next steps are opened and lined ({@link TunnelBlocks}) so the route is
 * clear whatever the crater looks like. A breach is only started when its face is at least 7 blocks from the player;
 * closer than that creepers behave like vanilla and a witch stays put, while endermen can still dig.</p>
 *
 * <p>Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class BreachHandler {
    private static final int CHECK_INTERVAL = 5;
    private static final int ENDER_INTERVAL = 14;
    private static final int BLAST_CELLS = 3;
    private static final int BLAST_COOLDOWN = 40;
    private static final int PRIME_HOLD = 100;           // a primed creeper that never blew up frees the slot after this
    private static final double SAFE_PLAYER_DIST_SQR = 7.0 * 7.0; // the blasts are real now: keep them off the player
    private static final float DYNAMITE_POWER = 2.0F;
    private static final double WITCH_MIN = 7.0;
    private static final double WITCH_MAX = 16.0;
    private static final int WITCH_COOLDOWN = 100;
    private static final int WITCH_PLAN_PAUSE = 60;      // after a throw, nobody else throws until it has landed
    private static final double IMPACT_TOLERANCE_SQR = 6.0 * 6.0;

    private static final String KEY_PRIMED = "curses_breach_primed";

    private static final class Stall {
        long at;
        double x;
        double y;
        double z;
        int still;
    }

    // Server thread only.
    private static final Map<Mob, Stall> STALL = new WeakHashMap<>();
    private static final Map<Witch, Long> WITCH_READY = new WeakHashMap<>();
    private static final Map<ThrownPotion, TunnelPlan> BREACH_POTIONS = new WeakHashMap<>();
    private static TunnelPlan throwing;
    /** True while a witch's tunnel dynamite is exploding, so Detonate can tell it from a normal blast. */
    private static boolean dynamiteBlast;

    /** Cells to open one tick after a real explosion, so the blast cannot undo the lining. */
    private record Pending(ServerLevel level, TunnelPlan plan, Vec3 at, long due) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private BreachHandler() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STALL.clear();
        WITCH_READY.clear();
        BREACH_POTIONS.clear();
        PENDING.clear();
        throwing = null;
        dynamiteBlast = false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Per-mob decisions
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        boolean isEnder = entity instanceof EnderMan;
        boolean isCreeper = entity instanceof Creeper;
        boolean isWitch = entity instanceof Witch;
        if (!isEnder && !isCreeper && !isWitch) {
            return;
        }
        if (entity.level().isClientSide() || !TunnelManager.working()) {
            return;
        }
        Mob mob = (Mob) entity;
        if ((mob.tickCount + mob.getId()) % CHECK_INTERVAL != 0 || !mob.isAlive()) {
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        Player prey = TunnelManager.preyFor(mob, level);
        if (prey == null) {
            return;
        }
        long now = level.getGameTime();
        TunnelPlan plan = TunnelManager.planFor(prey);
        if (plan == null || plan.level != level) {
            request(mob, level, prey, now, !isEnder);
            return;
        }
        if (!plan.frontierPending() || TunnelManager.jobRunning(prey.getUUID())) {
            return;
        }
        if (isEnder) {
            endermanDig(mob, level, prey, plan, now);
        } else if (isCreeper) {
            creeperPrime((Creeper) mob, prey, plan, now);
        } else {
            witchThrow((Witch) mob, level, prey, plan, now);
        }
    }

    /** A digger asks for a tunnel when it is underground, or (creepers, witches) has been stuck for a few seconds. */
    private static void request(Mob mob, ServerLevel level, Player prey, long now, boolean useStall) {
        if (mob.distanceToSqr(prey) < 36.0) {
            return;
        }
        boolean underground = !level.canSeeSky(mob.blockPosition());
        if (underground || (useStall && stalled(mob, now))) {
            TunnelManager.requestPlan(level, prey, mob.blockPosition());
        }
    }

    /** True once the mob has hardly moved for about six seconds (sampled every two seconds). */
    private static boolean stalled(Mob mob, long now) {
        Stall s = STALL.get(mob);
        if (s == null) {
            s = new Stall();
            s.at = now;
            s.x = mob.getX();
            s.y = mob.getY();
            s.z = mob.getZ();
            STALL.put(mob, s);
            return false;
        }
        if (now - s.at >= 40) {
            double dx = mob.getX() - s.x;
            double dy = mob.getY() - s.y;
            double dz = mob.getZ() - s.z;
            s.still = dx * dx + dy * dy + dz * dz < 2.25 ? s.still + 1 : 0;
            s.at = now;
            s.x = mob.getX();
            s.y = mob.getY();
            s.z = mob.getZ();
        }
        return s.still >= 3;
    }

    // ---------------------------------------------------------------------------------------------------
    // Endermen
    // ---------------------------------------------------------------------------------------------------

    private static void endermanDig(Mob mob, ServerLevel level, Player prey, TunnelPlan plan, long now) {
        if (mob.getTarget() != null || now < plan.nextDig) {
            return;
        }
        BlockPos last = plan.lastOpen();
        if (mob.distanceToSqr(last.getX() + 0.5, last.getY(), last.getZ() + 0.5) > 32.0 * 32.0) {
            return;
        }
        if (!TunnelManager.takeDig() || !TunnelManager.digNext(plan, prey, 1)) {
            return;
        }
        plan.nextDig = now + ENDER_INTERVAL;
        plan.lastActive = now;

        BlockPos at = plan.lastOpen();
        level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(), 12, 0.3, 0.6, 0.3, 0.1);
        mob.getNavigation().stop();
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, mob.getYRot(), mob.getXRot());
        mob.setDeltaMovement(Vec3.ZERO);
        mob.resetFallDistance();
    }

    // ---------------------------------------------------------------------------------------------------
    // Creepers
    // ---------------------------------------------------------------------------------------------------

    private static void creeperPrime(Creeper creeper, Player prey, TunnelPlan plan, long now) {
        if (creeper.isIgnited() || creeper.getSwellDir() > 0) {
            return;
        }
        if (now < plan.nextBlast || (plan.primedBy != null && now - plan.primedAt < PRIME_HOLD)) {
            return; // the tunnel's turn belongs to another creeper (or it is still cooling down)
        }
        BlockPos last = plan.lastOpen();
        BlockPos front = plan.frontier();
        if (front == null
                || creeper.distanceToSqr(last.getX() + 0.5, last.getY(), last.getZ() + 0.5) > 2.5 * 2.5
                || Math.abs(creeper.getY() - last.getY()) > 2.0) {
            return; // not at the face of the tunnel yet
        }
        if (front.distSqr(prey.blockPosition()) < SAFE_PLAYER_DIST_SQR) {
            return; // too close to the player: vanilla creeper behaviour
        }
        creeper.getPersistentData().putBoolean(KEY_PRIMED, true);
        plan.primedBy = creeper.getUUID();
        plan.primedAt = now;
        plan.lastActive = now;
        creeper.ignite(); // vanilla fuse, hiss and flashing (Quick Fuse still applies); the blast is swapped in below
    }

    /**
     * A primed creeper explodes for real (vanilla blast, damage, crater). One tick later the tunnel's next steps are
     * opened and lined, so the route is guaranteed to be clear whatever the blast did.
     */
    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getExplosion().getDirectSourceEntity() instanceof Creeper creeper)
                || !creeper.getPersistentData().getBoolean(KEY_PRIMED)) {
            return;
        }
        TunnelPlan plan = TunnelManager.planPrimedBy(creeper.getUUID());
        if (plan == null) {
            return;
        }
        plan.primedBy = null;
        queue(level, plan, creeper.position());
    }

    /** Hostile mobs are not hurt by the tunnel blasts (witches, the queue behind the head); players and others are. */
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        boolean tunnelBlast = dynamiteBlast
                || (event.getExplosion().getDirectSourceEntity() instanceof Creeper creeper
                        && creeper.getPersistentData().getBoolean(KEY_PRIMED));
        if (tunnelBlast) {
            event.getAffectedEntities().removeIf(e -> e instanceof Enemy);
        }
    }

    private static void queue(ServerLevel level, TunnelPlan plan, Vec3 at) {
        PENDING.add(new Pending(level, plan, at, level.getGameTime() + 1));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || PENDING.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext();) {
            Pending p = it.next();
            if (p.level() != level || now < p.due()) {
                continue;
            }
            it.remove();
            openAfterBlast(level, p.plan(), p.at());
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Witches
    // ---------------------------------------------------------------------------------------------------

    private static void witchThrow(Witch witch, ServerLevel level, Player prey, TunnelPlan plan, long now) {
        if (witch.isDrinkingPotion() || now < plan.nextBlast) {
            return;
        }
        Long ready = WITCH_READY.get(witch);
        if (ready != null && now < ready) {
            return;
        }
        BlockPos front = plan.frontier();
        BlockPos last = plan.lastOpen();
        if (front == null || front.distSqr(prey.blockPosition()) < SAFE_PLAYER_DIST_SQR) {
            return;
        }
        // Stays well back from the face it blasts: never closer than WITCH_MIN, and never from out of range.
        Vec3 aim = Vec3.atCenterOf(front);
        double distance = witch.position().distanceTo(aim);
        if (distance < WITCH_MIN || distance > WITCH_MAX) {
            return;
        }
        // Clear line down the tunnel to its last open cell, so the dynamite flies the tunnel and lands on the face.
        BlockHitResult hit = level.clip(new ClipContext(witch.getEyePosition(), Vec3.atCenterOf(last),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, witch));
        if (hit.getType() != HitResult.Type.MISS) {
            return;
        }

        throwing = plan;
        boolean thrown;
        try {
            thrown = Siege.lobPotion(witch, level, aim, new ItemStack(CursesItems.DYNAMITE.get()));
        } finally {
            throwing = null;
        }
        if (thrown) {
            WITCH_READY.put(witch, now + WITCH_COOLDOWN);
            plan.nextBlast = now + WITCH_PLAN_PAUSE;
            plan.lastActive = now;
        }
    }

    /** Tags the dynamite a witch throws at the tunnel, so it is handled here and not as a normal Ordnance blast. */
    @SubscribeEvent
    public static void onPotionJoin(EntityJoinLevelEvent event) {
        if (throwing != null && event.getEntity() instanceof ThrownPotion potion) {
            BREACH_POTIONS.put(potion, throwing);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.level() instanceof ServerLevel level)) {
            return;
        }
        TunnelPlan plan = BREACH_POTIONS.remove(potion);
        if (plan == null) {
            return;
        }
        event.setCanceled(true); // the Ordnance handler never sees it
        if (potion.isRemoved()) {
            return;
        }
        Vec3 at = event.getRayTraceResult().getLocation();
        dynamiteBlast = true;
        try {
            level.explode(potion, at.x, at.y, at.z, DYNAMITE_POWER, Level.ExplosionInteraction.MOB);
        } finally {
            dynamiteBlast = false;
        }
        potion.discard();
        BlockPos front = plan.frontier();
        if (!plan.dead && front != null && at.distanceToSqr(Vec3.atCenterOf(front)) <= IMPACT_TOLERANCE_SQR) {
            queue(level, plan, at);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The breach itself
    // ---------------------------------------------------------------------------------------------------

    /** Opens (and lines) the next stretch of the tunnel after a real explosion has gone off. */
    private static void openAfterBlast(ServerLevel level, TunnelPlan plan, Vec3 at) {
        if (plan.dead || !plan.frontierPending()) {
            return;
        }
        Player prey = level.getServer().getPlayerList().getPlayer(plan.playerId);
        BlockPos last = plan.lastOpen();
        if (prey == null || at.distanceToSqr(last.getX() + 0.5, last.getY(), last.getZ() + 0.5) > 8.0 * 8.0) {
            return;
        }
        if (TunnelManager.digNext(plan, prey, BLAST_CELLS)) {
            long now = level.getGameTime();
            plan.nextBlast = now + BLAST_COOLDOWN;
            plan.lastActive = now;
        }
    }
}
