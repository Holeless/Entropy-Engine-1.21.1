package com.example.curses.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Warping: undead mobs and piglins have a chance to spawn with an Ender Pearl in their off hand, and use it to
 * get to you.
 *
 * <p><b>Who can get one:</b> every undead mob except phantoms, skeleton/zombie horses and the Wither, plus
 * piglins and piglin brutes ({@code PiglinAiMixin} stops piglins throwing the pearl away). Each mob rolls once
 * (remembered in its saved data) and only gets a pearl if its off hand is empty. The pearl is not loot.</p>
 *
 * <p><b>When they throw:</b> a mob holding a pearl throws it (checked twice a second) at
 * <ul>
 *   <li>its target, when that is a player {@link #MIN_RANGE}-{@link #MAX_RANGE} blocks away and in sight
 *       ({@link #THROW_CHANCE} per check), or</li>
 *   <li>a player it cannot walk to (walled in, up a pillar, behind anything) within {@link #SIEGE_RANGE}
 *       blocks, in sight or not ({@link #SIEGE_THROW_CHANCE} per check). This is how a shelter gets pearled.</li>
 * </ul></p>
 *
 * <p><b>Where it lands (never wasted):</b> the throw is planned before it is made. The planner flies candidate
 * arcs through the world with the pearl's own physics (1.6 speed, 1% drag, 0.03 gravity) and checks every step
 * against the blocks, so it knows where each arc ends.</p>
 * <ul>
 *   <li><b>Window shot:</b> if some arc reaches the floor near the player without hitting anything on the way
 *       (an open window, a doorway, a hole in the roof), that arc is used with a
 *       {@link #WINDOW_CHANCE} chance, so the mob appears inside next to the player. It is a high chance, not
 *       a guarantee.</li>
 *   <li><b>Structure shot:</b> otherwise the pearl is thrown at the player and the mob lands on the outside of
 *       whatever is in the way: on the roof, or against the wall, as close to the player as possible.</li>
 * </ul>
 * <p>The mob is then put on exactly the planned spot when the pearl lands, wherever the pearl really ended up
 * (a window frame, a gust of bad luck), so the pearl always does what the planner promised. The pearl is used up;
 * a mob throws at most one.</p>
 *
 * <p>Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class WarpingHandler {
    /** Chance for each eligible mob to get an ender pearl. */
    public static final float CHANCE = 0.10F;

    private static final String KEY_ROLLED = "curses_warping_rolled";

    private static final int CHECK_INTERVAL = 10;        // ticks between throw decisions
    private static final double MIN_RANGE = 10.0;        // closer than this it simply walks
    private static final double MAX_RANGE = 32.0;
    private static final double SIEGE_RANGE = 24.0;      // a player that cannot be walked to, in sight or not
    private static final float THROW_CHANCE = 0.3F;      // per check, target in sight
    private static final float SIEGE_THROW_CHANCE = 0.5F; // per check, player cannot be walked to
    private static final float WINDOW_CHANCE = 0.8F;     // an easy way inside is taken this often
    private static final int RETRY_DELAY = 60;           // ticks to wait after finding no way to throw

    // Pearl physics (ThrowableProjectile): move, then 1% drag, then gravity.
    private static final double THROW_SPEED = 1.6;
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.03;
    private static final int TRACE_TICKS = 160;

    private static final double AIM_TOLERANCE = 0.8;     // blocks of landing error accepted by the angle search
    private static final double INSIDE_HORIZONTAL = 3.5; // an inside landing is this close to the player sideways...
    private static final double INSIDE_VERTICAL = 1.5;   // ... and this close in height
    private static final double STRUCTURE_MAX = 12.0;    // a structure shot must land this close to the player
    private static final double HIT_TOLERANCE = 3.0;     // how far from its aim point an arc may end and still count

    /** Aim points around the player tried for a window shot: centre first, then a ring of 8 at 1.5 and 3 blocks. */
    private static final double[][] AIMS = buildAims();

    private record Plan(Vec3 velocity, Vec3 landing) {
    }

    private record Warp(Vec3 landing, Player player) {
    }

    // Server thread only.
    private static final Map<ThrownEnderpearl, Warp> PLANNED = new WeakHashMap<>();
    private static final Map<Mob, Long> NEXT_TRY = new WeakHashMap<>();

    private WarpingHandler() {
    }

    private static double[][] buildAims() {
        List<double[]> list = new ArrayList<>();
        list.add(new double[] {0.0, 0.0});
        for (double radius : new double[] {1.5, 3.0}) {
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0;
                list.add(new double[] {Math.cos(angle) * radius, Math.sin(angle) * radius});
            }
        }
        return list.toArray(new double[0][]);
    }

    private static boolean isEligible(Mob mob) {
        if (mob instanceof Phantom || mob instanceof AbstractHorse || mob instanceof WitherBoss) {
            return false;
        }
        return mob.getType().is(EntityTypeTags.UNDEAD) || mob instanceof AbstractPiglin;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLANNED.clear();
        NEXT_TRY.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Spawning
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob) || !isEligible(mob)) {
            return;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(KEY_ROLLED) || !CurseData.isActive(level.getServer(), Curses.WARPING)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);
        if (mob.getRandom().nextFloat() >= CHANCE || !mob.getOffhandItem().isEmpty()) {
            return;
        }

        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ENDER_PEARL));
        mob.setDropChance(EquipmentSlot.OFFHAND, 0.0F); // the curse's pearl is not loot
        CursesMod.LOGGER.debug("Warping: gave an ender pearl to {} at {}", mob.getType(), mob.blockPosition());
    }

    // ---------------------------------------------------------------------------------------------------
    // Deciding to throw
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onMobTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        if (mob.tickCount % CHECK_INTERVAL != 0 || !mob.isAlive() || !mob.getOffhandItem().is(Items.ENDER_PEARL)) {
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        if (!CurseData.isActive(level.getServer(), Curses.WARPING)) {
            return;
        }
        // Drawing a bow or similar: finish that first.
        if (mob.isPassenger() || mob.isUsingItem()) {
            return;
        }
        long now = level.getGameTime();
        Long wait = NEXT_TRY.get(mob);
        if (wait != null && now < wait) {
            return;
        }

        Player player = null;
        boolean siege = false;
        float chance = THROW_CHANCE;

        // 1. Its own target, in sight, at a distance worth a pearl.
        if (targetOf(mob) instanceof Player target && valid(mob, target)) {
            double distSqr = mob.distanceToSqr(target);
            if (distSqr >= MIN_RANGE * MIN_RANGE && distSqr <= MAX_RANGE * MAX_RANGE && mob.hasLineOfSight(target)) {
                player = target;
            }
        }
        // 2. Otherwise a player it cannot walk to (vanilla drops a target it cannot see, so there may be none).
        if (player == null) {
            Player near = level.getNearestPlayer(mob, SIEGE_RANGE);
            if (near == null || !valid(mob, near) || mob.distanceToSqr(near) < MIN_RANGE * MIN_RANGE) {
                return;
            }
            player = near;
            siege = true;
            chance = SIEGE_THROW_CHANCE;
        }

        if (mob.getRandom().nextFloat() >= chance) {
            return;
        }
        // The path search is the costly part, so it only happens after the roll.
        if (siege && canWalkTo(mob, player)) {
            return;
        }
        if (!throwPearl(mob, level, player)) {
            NEXT_TRY.put(mob, now + RETRY_DELAY);
        }
    }

    private static boolean valid(Mob mob, Player target) {
        return target.isAlive() && !target.isSpectator() && !target.isCreative() && target.level() == mob.level();
    }

    private static boolean canWalkTo(Mob mob, Player player) {
        Path path = mob.getNavigation().createPath(player, 0);
        return path != null && path.canReach();
    }

    // ---------------------------------------------------------------------------------------------------
    // Throwing
    // ---------------------------------------------------------------------------------------------------

    private static boolean throwPearl(Mob mob, ServerLevel level, Player player) {
        ThrownEnderpearl pearl = new ThrownEnderpearl(level, mob);
        Plan plan = plan(level, mob, player, pearl.position());
        if (plan == null) {
            return false;
        }

        Vec3 v = plan.velocity();
        pearl.shoot(v.x, v.y, v.z, (float) THROW_SPEED, 0.0F); // no inaccuracy: the arc is the planned one
        PLANNED.put(pearl, new Warp(plan.landing(), player));

        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY); // used up
        mob.swing(InteractionHand.OFF_HAND);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE,
                0.5F, 0.4F / (mob.getRandom().nextFloat() * 0.4F + 0.8F));
        level.addFreshEntity(pearl);
        CursesMod.LOGGER.debug("Warping: {} threw an ender pearl at {} blocks, landing at {}", mob.getType(),
                (int) Math.sqrt(mob.distanceToSqr(player)), plan.landing());
        return true;
    }

    /** Puts the mob on the planned spot the moment its pearl lands, wherever the pearl really came down. */
    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownEnderpearl pearl) || !(pearl.level() instanceof ServerLevel level)) {
            return;
        }
        Warp warp = PLANNED.remove(pearl);
        if (warp == null) {
            return;
        }
        event.setCanceled(true);
        if (pearl.isRemoved()) {
            return;
        }
        if (pearl.getOwner() instanceof Mob mob && mob.isAlive() && mob.level() == level) {
            warp(level, mob, warp.landing());
            if (warp.player().isAlive() && !warp.player().isCreative() && !warp.player().isSpectator()) {
                mob.setTarget(warp.player());
            }
        }
        pearl.discard();
    }

    private static void warp(ServerLevel level, Mob mob, Vec3 to) {
        level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(), 24, 0.3, 0.6, 0.3, 0.1);
        mob.getNavigation().stop();
        mob.moveTo(to.x, to.y, to.z, mob.getYRot(), mob.getXRot());
        mob.setDeltaMovement(Vec3.ZERO);
        mob.resetFallDistance();
        level.sendParticles(ParticleTypes.PORTAL, to.x, to.y + 1.0, to.z, 24, 0.3, 0.6, 0.3, 0.1);
        level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
    }

    /** The mob's attack target. Piglins keep theirs in the brain instead of the usual target field. */
    private static LivingEntity targetOf(Mob mob) {
        LivingEntity target = mob.getTarget();
        // Only piglins have this memory registered; asking any other brain for it throws.
        if (target == null && mob instanceof AbstractPiglin
                && mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        }
        return target;
    }

    // ---------------------------------------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------------------------------------

    private static Plan plan(ServerLevel level, Mob mob, Player player, Vec3 origin) {
        Plan inside = windowShot(level, mob, player, origin);
        if (inside != null && mob.getRandom().nextFloat() < WINDOW_CHANCE) {
            return inside;
        }
        Plan outside = structureShot(level, mob, player, origin);
        if (outside != null) {
            return outside;
        }
        if (inside != null) {
            return inside; // the only way there is, so take it despite the roll
        }
        return roofShot(level, mob, player, origin);
    }

    /** An arc that comes down on the floor near the player without touching anything on the way. */
    private static Plan windowShot(ServerLevel level, Mob mob, Player player, Vec3 origin) {
        Vec3 feet = player.position();
        for (double[] aim : AIMS) {
            Vec3 point = new Vec3(feet.x + aim[0], feet.y + 0.5, feet.z + aim[1]);
            for (Vec3 velocity : velocities(origin, point)) {
                BlockHitResult hit = trace(level, mob, origin, velocity);
                if (hit == null || hit.getLocation().distanceTo(point) > HIT_TOLERANCE + 1.0) {
                    continue;
                }
                Vec3 stand = standingSpot(level, mob, hit, feet);
                if (stand == null) {
                    continue;
                }
                double dx = stand.x - feet.x;
                double dz = stand.z - feet.z;
                if (Math.sqrt(dx * dx + dz * dz) > INSIDE_HORIZONTAL || Math.abs(stand.y - feet.y) > INSIDE_VERTICAL) {
                    continue;
                }
                // "Inside" means under the same cover as the player: a spot outside the wall or on the roof sees
                // the sky when the player does not, so that is a structure shot, not a window shot.
                if (level.canSeeSky(BlockPos.containing(stand)) != level.canSeeSky(player.blockPosition())) {
                    continue;
                }
                return new Plan(velocity, stand);
            }
        }
        return null;
    }

    /** Thrown straight at the player: the mob lands on the outside of whatever stops the pearl, nearest the player. */
    private static Plan structureShot(ServerLevel level, Mob mob, Player player, Vec3 origin) {
        Vec3 feet = player.position();
        Vec3 point = new Vec3(feet.x, feet.y + 1.0, feet.z);
        Plan best = null;
        double bestDistance = STRUCTURE_MAX;
        for (Vec3 velocity : velocities(origin, point)) {
            BlockHitResult hit = trace(level, mob, origin, velocity);
            if (hit == null || hit.getDirection() == Direction.DOWN) {
                continue;
            }
            Vec3 stand = standingSpot(level, mob, hit, feet);
            if (stand == null) {
                continue;
            }
            double distance = stand.distanceTo(feet);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Plan(velocity, stand);
            }
        }
        return best;
    }

    /** Last resort: the top of the ground or roof a few blocks from the player. */
    private static Plan roofShot(ServerLevel level, Mob mob, Player player, Vec3 origin) {
        RandomSource random = mob.getRandom();
        Vec3 feet = player.position();
        for (int i = 0; i < 20; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 2.5 + random.nextDouble() * 4.0;
            int x = (int) Math.floor(feet.x + Math.cos(angle) * radius);
            int z = (int) Math.floor(feet.z + Math.sin(angle) * radius);
            if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            Vec3 stand = findStand(level, mob, new BlockPos(x, top, z), feet);
            if (stand == null) {
                continue;
            }
            Vec3 point = stand.add(0.0, 0.3, 0.0);
            for (Vec3 velocity : velocities(origin, point)) {
                BlockHitResult hit = trace(level, mob, origin, velocity);
                if (hit != null && hit.getLocation().distanceTo(point) <= HIT_TOLERANCE) {
                    return new Plan(velocity, stand);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------
    // Arcs
    // ---------------------------------------------------------------------------------------------------

    /**
     * Launch velocities that bring a pearl down on {@code aim}: the flattest and the highest of the arcs that do
     * (a flat shot and a lob), found by flying the pearl in free air for every quarter degree.
     */
    private static List<Vec3> velocities(Vec3 origin, Vec3 aim) {
        double dx = aim.x - origin.x;
        double dz = aim.z - origin.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double dy = aim.y - origin.y;
        List<Vec3> result = new ArrayList<>();
        if (distance < 1.0) {
            return result;
        }
        double ux = dx / distance;
        double uz = dz / distance;

        int steps = (85 + 25) * 4 + 1;
        double[] error = new double[steps];
        for (int i = 0; i < steps; i++) {
            error[i] = landingError(Math.toRadians(-25.0 + i * 0.25), distance, dy);
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < steps; i++) {
            double previous = i > 0 ? error[i - 1] : Double.MAX_VALUE;
            double next = i < steps - 1 ? error[i + 1] : Double.MAX_VALUE;
            if (error[i] <= AIM_TOLERANCE && error[i] <= previous && error[i] <= next) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first >= 0) {
            result.add(velocity(Math.toRadians(-25.0 + first * 0.25), ux, uz));
        }
        if (last > first) {
            result.add(velocity(Math.toRadians(-25.0 + last * 0.25), ux, uz));
        }
        return result;
    }

    private static Vec3 velocity(double angle, double ux, double uz) {
        double cos = Math.cos(angle);
        return new Vec3(ux * cos * THROW_SPEED, Math.sin(angle) * THROW_SPEED, uz * cos * THROW_SPEED);
    }

    /** How far from {@code distance} (at height {@code dy}) a pearl launched at this angle comes down, in free air. */
    private static double landingError(double angle, double distance, double dy) {
        double vx = THROW_SPEED * Math.cos(angle);
        double vy = THROW_SPEED * Math.sin(angle);
        double x = 0.0;
        double y = 0.0;
        for (int tick = 0; tick < TRACE_TICKS; tick++) {
            double px = x;
            double py = y;
            x += vx;
            y += vy;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (py > dy && y <= dy) {
                double landed = px + (x - px) * ((py - dy) / (py - y));
                return Math.abs(landed - distance);
            }
        }
        return Double.MAX_VALUE;
    }

    /** Flies a pearl through the real blocks. Returns where it first hits one, or null (never lands / unloaded). */
    private static BlockHitResult trace(ServerLevel level, Mob mob, Vec3 from, Vec3 velocity) {
        Vec3 p = from;
        Vec3 v = velocity;
        for (int tick = 0; tick < TRACE_TICKS; tick++) {
            Vec3 next = p.add(v);
            if (!level.hasChunkAt(BlockPos.containing(next))) {
                return null; // never make the world load or generate chunks for this
            }
            BlockHitResult hit = level.clip(new ClipContext(p, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
            if (hit.getType() != HitResult.Type.MISS) {
                return hit;
            }
            p = next;
            v = new Vec3(v.x * DRAG, v.y * DRAG - GRAVITY, v.z * DRAG);
            if (p.y < level.getMinBuildHeight()) {
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------
    // Landing spots
    // ---------------------------------------------------------------------------------------------------

    /** Where the mob ends up when the pearl hits here: next to the face it hit, on something it can stand on. */
    private static Vec3 standingSpot(ServerLevel level, Mob mob, BlockHitResult hit, Vec3 avoid) {
        Direction face = hit.getDirection();
        Vec3 at = hit.getLocation().add(face.getStepX() * 0.1, face.getStepY() * 0.1, face.getStepZ() * 0.1);
        return findStand(level, mob, BlockPos.containing(at), avoid);
    }

    private static final int[][] RING = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] DY = {0, -1, -2, -3, 1, 2};

    /** A spot the mob fits and stands on, at or near {@code origin} (not on top of {@code avoid}), or null. */
    private static Vec3 findStand(ServerLevel level, Mob mob, BlockPos origin, Vec3 avoid) {
        for (int[] offset : RING) {
            for (int dy : DY) {
                BlockPos feet = origin.offset(offset[0], dy, offset[1]);
                if (!level.hasChunkAt(feet)) {
                    continue;
                }
                Vec3 spot = Vec3.atBottomCenterOf(feet);
                if (avoid != null && spot.distanceToSqr(avoid) < 0.8) {
                    continue;
                }
                if (canStand(level, mob, feet, spot)) {
                    return spot;
                }
            }
        }
        return null;
    }

    private static boolean canStand(ServerLevel level, Mob mob, BlockPos feet, Vec3 spot) {
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP) || !floor.getFluidState().isEmpty()) {
            return false;
        }
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS) || floor.is(BlockTags.CAMPFIRES)) {
            return false;
        }
        BlockState at = level.getBlockState(feet);
        if (at.is(BlockTags.FIRE) || !level.getFluidState(feet).isEmpty()) {
            return false;
        }
        AABB box = mob.getDimensions(mob.getPose()).makeBoundingBox(spot);
        return level.noCollision(mob, box);
    }
}
