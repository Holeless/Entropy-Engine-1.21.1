package com.example.curses.effect.breach;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Breach, the Endermen: they move blocks you placed and build pathways for mobs that cannot reach you.
 *
 * <p><b>When:</b> an Enderman that is not angry, with a player within {@link #PLAYER_RANGE} blocks, looks (once a
 * second) for a hostile walking mob that is hunting that player from within {@link #WALKER_RANGE} blocks sideways
 * and has no way to walk to them. If it finds one, it plans a way for it.</p>
 *
 * <p><b>The plan:</b> a line from the player toward the mob. While the ground keeps going at the player's height
 * (a platform, a roof, a hillside) nothing is needed. Where it ends, the way steps down one block per column
 * (a staircase mobs can walk, one block high per step) until it reaches the mob's height, and then goes level (a
 * bridge over any gap). The first column that is missing its floor is where the next block goes. If the way is
 * blocked by a block <i>you placed</i> (a wall, a door frame) the Enderman takes that block away instead; if it is
 * blocked by anything else (natural rock) there is no plan, that is a job for the undead hands in
 * {@code BlockBreakerHandler}.</p>
 *
 * <p><b>The blocks:</b> an Enderman picks up the nearest block you placed within {@link #SOURCE_RADIUS} blocks (your
 * walls, your pillar), carries it to the gap like vanilla Endermen carry blocks, and puts it down there. If there
 * is nothing you placed nearby it makes do with the natural blocks vanilla Endermen already pick up (dirt, grass,
 * sand-less ground, clay...). It never takes blocks that are not a plain full cube (no chests, no sand or
 * gravel, no TNT), never takes the block under a player's feet, and never takes a block it placed itself.</p>
 *
 * <p>Respects {@code mobGriefing}, does at most {@link #DECISION_BUDGET} decisions and {@link #PATH_BUDGET} path
 * searches per server tick, and moves by teleporting next to the work, with the usual Enderman effects. Which
 * blocks are "yours" is kept by {@link PlacedBlocks} (saved with the world; blocks placed before the mod was
 * installed are not known). Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class EndermanBuilderHandler {
    private static final int CHECK_INTERVAL = 20;
    private static final double PLAYER_RANGE = 40.0;
    private static final double WALKER_RANGE = 24.0;
    private static final double MIN_WALKER_DIST = 3.0;
    private static final int MAX_STEPS = 128;
    private static final int SOURCE_RADIUS = 6;
    private static final int DECISION_BUDGET = 2;
    private static final int PATH_BUDGET = 1;
    private static final long WALKER_OK_TICKS = 200L;   // a mob that can walk to the player is not checked again for this long
    private static final double NEAR_SQR = 5.0 * 5.0;   // closer than this to the work, it just works from where it is
    private static final int MAX_BUILT = 4000;

    private record Step(BlockPos pos, boolean clear) {
    }

    // Server thread only.
    private static final Map<Mob, Long> WALKER_OK = new WeakHashMap<>();
    /** Blocks the Endermen put down themselves: never picked up again. */
    private static final LongOpenHashSet BUILT = new LongOpenHashSet();
    private static int decisionBudget;
    private static int pathBudget;

    private EndermanBuilderHandler() {
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        decisionBudget = DECISION_BUDGET;
        pathBudget = PATH_BUDGET;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WALKER_OK.clear();
        BUILT.clear();
        decisionBudget = 0;
        pathBudget = 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Remembering which blocks players placed
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player && event.getLevel() instanceof ServerLevel level) {
            PlacedBlocks.get(level).add(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PlacedBlocks.get(level).remove(event.getPos());
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Deciding what to do
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof EnderMan enderman) || enderman.level().isClientSide()) {
            return;
        }
        if (decisionBudget <= 0 || (enderman.tickCount + enderman.getId()) % CHECK_INTERVAL != 0
                || !enderman.isAlive() || enderman.isPassenger() || enderman.getTarget() != null) {
            return;
        }
        ServerLevel level = (ServerLevel) enderman.level();
        if (!CurseData.isActive(level.getServer(), Curses.BREACH)
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }
        Player prey = level.getNearestPlayer(enderman, PLAYER_RANGE);
        if (prey == null || !prey.isAlive() || prey.isCreative() || prey.isSpectator()) {
            return;
        }
        decisionBudget--;

        Mob walker = findStuckWalker(level, prey);
        if (walker == null) {
            return;
        }
        Step step = plan(level, prey, walker);
        if (step == null) {
            return;
        }
        if (step.clear()) {
            clearBlock(level, enderman, step.pos());
        } else {
            build(level, enderman, prey, step.pos());
        }
    }

    /** A hostile walking mob that is hunting this player and cannot walk to them, or null. */
    private static Mob findStuckWalker(ServerLevel level, Player prey) {
        AABB box = prey.getBoundingBox().inflate(WALKER_RANGE, 128.0, WALKER_RANGE);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy
                && !(m instanceof EnderMan)
                && m.isAlive()
                && m.getTarget() == prey
                && m.getNavigation() instanceof GroundPathNavigation
                && horizontalSqr(m, prey) <= WALKER_RANGE * WALKER_RANGE
                && horizontalSqr(m, prey) >= MIN_WALKER_DIST * MIN_WALKER_DIST);
        mobs.sort(Comparator.comparingDouble(m -> horizontalSqr(m, prey)));

        long now = level.getGameTime();
        for (Mob mob : mobs) {
            Long ok = WALKER_OK.get(mob);
            if (ok != null && now < ok) {
                continue;
            }
            if (pathBudget <= 0) {
                return null;
            }
            pathBudget--;
            Path path = mob.getNavigation().createPath(prey, 0);
            if (path != null && path.canReach()) {
                WALKER_OK.put(mob, now + WALKER_OK_TICKS);
                continue;
            }
            return mob;
        }
        return null;
    }

    private static double horizontalSqr(Mob mob, Player player) {
        double dx = mob.getX() - player.getX();
        double dz = mob.getZ() - player.getZ();
        return dx * dx + dz * dz;
    }

    // ---------------------------------------------------------------------------------------------------
    // Planning the pathway
    // ---------------------------------------------------------------------------------------------------

    /** The next thing that has to happen to give the walker a way to the player, or null (nothing to do / not possible). */
    private static Step plan(ServerLevel level, Player prey, Mob walker) {
        BlockPos from = prey.blockPosition();
        BlockPos to = (walker.getVehicle() != null ? walker.getVehicle() : walker).blockPosition();
        int top = from.getY();
        int ground = to.getY();
        if (ground > top) {
            return null; // the mob is above the player: not a job for stairs
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int total = Math.max(Math.abs(dx), Math.abs(dz));
        if (total < 2) {
            return null;
        }
        // Long enough for the staircase to come all the way down, even if the mob is close to the foot of the tower.
        int limit = Math.min(MAX_STEPS, Math.max(total, top - ground) + 1);

        PlacedBlocks placed = PlacedBlocks.get(level);
        int y = top;
        for (int i = 1; i <= limit; i++) {
            int x = from.getX() + Math.round((float) dx * i / total);
            int z = from.getZ() + Math.round((float) dz * i / total);
            if (!level.hasChunkAt(new BlockPos(x, y, z))) {
                return null;
            }
            if (walkable(level, x, y, z)) {
                continue; // the ground goes on at this height
            }
            if (y > ground) {
                y--; // step down one block
            }
            BlockPos feet = new BlockPos(x, y, z);
            if (level.isOutsideBuildHeight(feet.below())) {
                return null;
            }
            for (int k = 0; k < 3; k++) {
                BlockPos cell = feet.above(k);
                BlockState state = level.getBlockState(cell);
                if (!state.getCollisionShape(level, cell).isEmpty()) {
                    // In the way: a block you placed gets moved, anything else ends the plan.
                    boolean movable = placed.contains(cell) && BlockBreakerHandler.breakable(level, cell, state);
                    return movable ? new Step(cell, true) : null;
                }
            }
            BlockPos floor = feet.below();
            BlockState floorState = level.getBlockState(floor);
            if (!floorState.isFaceSturdy(level, floor, Direction.UP) || !floorState.getFluidState().isEmpty()) {
                return new Step(floor, false);
            }
        }
        return null;
    }

    /** Free body room (three blocks) and a solid, dry floor at this height in this column. */
    private static boolean walkable(ServerLevel level, int x, int y, int z) {
        for (int k = 0; k < 3; k++) {
            BlockPos cell = new BlockPos(x, y + k, z);
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return false;
            }
        }
        BlockPos floor = new BlockPos(x, y - 1, z);
        BlockState state = level.getBlockState(floor);
        return state.isFaceSturdy(level, floor, Direction.UP) && state.getFluidState().isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------
    // Doing the work
    // ---------------------------------------------------------------------------------------------------

    /** Takes a block of yours out of the way (carries it if its hands are free, otherwise it just breaks). */
    private static void clearBlock(ServerLevel level, EnderMan enderman, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!BlockBreakerHandler.breakable(level, pos, state) || !moveNear(level, enderman, pos)) {
            return;
        }
        PlacedBlocks.get(level).remove(pos);
        if (enderman.getCarriedBlock() == null && usable(state, level, pos)) {
            enderman.setCarriedBlock(state);
            level.removeBlock(pos, false);
            puff(level, pos);
        } else {
            level.destroyBlock(pos, true, enderman);
        }
    }

    /** Puts a block down at {@code floor}; picks one up first if its hands are empty. */
    private static void build(ServerLevel level, EnderMan enderman, Player prey, BlockPos floor) {
        BlockState carried = enderman.getCarriedBlock();
        if (carried == null || !usable(carried, level, floor)) {
            BlockPos source = findSource(level, enderman, prey);
            if (source == null) {
                return;
            }
            BlockState state = level.getBlockState(source);
            enderman.setCarriedBlock(state);
            level.removeBlock(source, false);
            PlacedBlocks.get(level).remove(source);
            puff(level, source);
            return; // it puts the block down on its next look
        }
        if (!level.isUnobstructed(carried, floor, CollisionContext.empty()) || !moveNear(level, enderman, floor)) {
            return;
        }
        level.setBlockAndUpdate(floor, carried);
        enderman.setCarriedBlock(null);
        if (BUILT.size() > MAX_BUILT) {
            BUILT.clear();
        }
        BUILT.add(floor.asLong());
        level.playSound(null, floor, carried.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        puff(level, floor);
    }

    /** A plain full cube that is safe to put down as a floor. */
    private static boolean usable(BlockState state, ServerLevel level, BlockPos pos) {
        return !state.isAir() && !state.hasBlockEntity() && !(state.getBlock() instanceof FallingBlock)
                && !state.is(Blocks.TNT) && state.isCollisionShapeFullBlock(level, pos);
    }

    /** The nearest block to pick up: one you placed first, otherwise natural ground an Enderman may carry. */
    private static BlockPos findSource(ServerLevel level, EnderMan enderman, Player prey) {
        PlacedBlocks placed = PlacedBlocks.get(level);
        BlockPos origin = enderman.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -SOURCE_RADIUS; dx <= SOURCE_RADIUS; dx++) {
            for (int dz = -SOURCE_RADIUS; dz <= SOURCE_RADIUS; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
                    continue;
                }
                for (int dy = -3; dy <= 4; dy++) {
                    cursor.set(x, origin.getY() + dy, z);
                    if (level.isOutsideBuildHeight(cursor) || BUILT.contains(cursor.asLong())) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    boolean yours = placed.contains(cursor);
                    if (!yours && !state.is(BlockTags.ENDERMAN_HOLDABLE)) {
                        continue;
                    }
                    if (!usable(state, level, cursor) || !BlockBreakerHandler.breakable(level, cursor, state)) {
                        continue;
                    }
                    // Not the floor a player is standing on.
                    if (cursor.getY() < prey.getY() && Math.abs(cursor.getX() + 0.5 - prey.getX()) < 1.5
                            && Math.abs(cursor.getZ() + 0.5 - prey.getZ()) < 1.5) {
                        continue;
                    }
                    if (!exposed(level, cursor)) {
                        continue;
                    }
                    double score = origin.distSqr(cursor) + (yours ? 0.0 : 400.0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** At least one side of the block is open, so it can really be picked up (not buried inside a mass of rock). */
    private static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (level.hasChunkAt(next) && level.getBlockState(next).getCollisionShape(level, next).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Moving
    // ---------------------------------------------------------------------------------------------------

    /** Makes sure the Enderman is within reach of {@code pos}, teleporting next to it if needed. */
    private static boolean moveNear(ServerLevel level, EnderMan enderman, BlockPos pos) {
        if (enderman.distanceToSqr(Vec3.atCenterOf(pos)) <= NEAR_SQR) {
            return true;
        }
        Vec3 spot = findStand(level, enderman, pos);
        if (spot == null) {
            return false;
        }
        level.sendParticles(ParticleTypes.PORTAL, enderman.getX(), enderman.getY() + 1.0, enderman.getZ(), 24, 0.3, 0.6, 0.3, 0.1);
        enderman.getNavigation().stop();
        enderman.moveTo(spot.x, spot.y, spot.z, enderman.getYRot(), enderman.getXRot());
        enderman.setDeltaMovement(Vec3.ZERO);
        enderman.resetFallDistance();
        level.sendParticles(ParticleTypes.PORTAL, spot.x, spot.y + 1.0, spot.z, 24, 0.3, 0.6, 0.3, 0.1);
        level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
        return true;
    }

    /** A spot an Enderman fits and stands on, one or two blocks from {@code around} (not on it), or null. */
    private static Vec3 findStand(ServerLevel level, EnderMan enderman, BlockPos around) {
        int[] heights = {1, 2, 0, 3};
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy : heights) {
                        BlockPos feet = around.offset(dx, dy, dz);
                        if (!level.hasChunkAt(feet)) {
                            continue;
                        }
                        BlockPos below = feet.below();
                        BlockState floor = level.getBlockState(below);
                        if (!floor.isFaceSturdy(level, below, Direction.UP) || !floor.getFluidState().isEmpty()) {
                            continue;
                        }
                        Vec3 spot = Vec3.atBottomCenterOf(feet);
                        AABB box = enderman.getDimensions(enderman.getPose()).makeBoundingBox(spot);
                        if (level.noCollision(enderman, box)) {
                            return spot;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void puff(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 16, 0.3, 0.3, 0.3, 0.1);
    }
}
