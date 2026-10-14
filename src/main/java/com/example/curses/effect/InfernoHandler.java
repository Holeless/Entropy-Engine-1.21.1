package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Inferno: blazes and magma cubes spawn in lava pools in the overworld.
 *
 * <p>A spawner of its own. Every 5-10 seconds each player gets one try: it looks for a lava pool 20-48 blocks
 * away (within {@link #UP} blocks above and {@link #DOWN} below the player), i.e. a lava source with open
 * air above it and at least {@link #POOL_MIN} lava blocks in the 5x5 around it, so a stray flowing trickle does
 * not count. A blaze appears in the air a few blocks over the pool, or a magma cube in the lava itself. No more
 * than {@link #MAX_NEARBY} of them are kept around a player. They despawn like any other hostile mob.</p>
 *
 * <p>Only loaded, entity-ticking chunks are touched. Respects peaceful difficulty and the {@code doMobSpawning}
 * game rule. Overworld and server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class InfernoHandler {
    private static final int MIN_INTERVAL = 100;      // ticks
    private static final int RANDOM_INTERVAL = 100;
    private static final int ATTEMPTS = 24;           // pool searches per player per try
    private static final int MIN_DIST = 20;           // sideways distance from the player
    private static final int MAX_DIST = 48;
    private static final int UP = 16;                 // search range above the player's feet
    private static final int DOWN = 32;               // ... and below
    private static final int MAX_NEARBY = 6;          // blazes + magma cubes within 64 blocks of a player
    private static final int POOL_RADIUS = 2;         // 5x5 around the lava surface block
    private static final int POOL_MIN = 12;           // of those 25 columns, this many must be lava
    private static final float BLAZE_CHANCE = 0.5F;   // otherwise a magma cube

    private static int cooldown = 0;

    private InfernoHandler() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!CurseData.isActive(level.getServer(), Curses.INFERNO)) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }

        cooldown = MIN_INTERVAL + level.random.nextInt(RANDOM_INTERVAL);
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                trySpawn(level, player);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cooldown = 0;
    }

    private static void trySpawn(ServerLevel level, ServerPlayer player) {
        AABB around = player.getBoundingBox().inflate(64.0);
        if (level.getEntitiesOfClass(Mob.class, around, m -> m instanceof Blaze || m instanceof MagmaCube).size() >= MAX_NEARBY) {
            return;
        }

        RandomSource random = level.random;
        BlockPos origin = player.blockPosition();
        for (int i = 0; i < ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = MIN_DIST + random.nextDouble() * (MAX_DIST - MIN_DIST);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);

            BlockPos surface = findLavaSurface(level, x, origin.getY(), z);
            if (surface != null && isPool(level, surface) && spawnAt(level, surface)) {
                return;
            }
        }
    }

    /** The highest lava source with air above it in this column near the player's height, or null. */
    private static BlockPos findLavaSurface(ServerLevel level, int x, int originY, int z) {
        if (!level.hasChunkAt(new BlockPos(x, originY, z))) {
            return null;
        }
        int top = Math.min(level.getMaxBuildHeight() - 2, originY + UP);
        int bottom = Math.max(level.getMinBuildHeight() + 1, originY - DOWN);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.LAVA) && fluid.isSource() && level.getBlockState(pos.above()).isAir()) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** Enough lava around this surface block to be a pool rather than a trickle. */
    private static boolean isPool(ServerLevel level, BlockPos surface) {
        // Never read a chunk that is not loaded (that would force it to load or generate).
        for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx += 2 * POOL_RADIUS) {
            for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz += 2 * POOL_RADIUS) {
                if (!level.hasChunkAt(surface.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        int lava = 0;
        for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx++) {
            for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz++) {
                if (level.getFluidState(surface.offset(dx, 0, dz)).is(FluidTags.LAVA)) {
                    lava++;
                }
            }
        }
        return lava >= POOL_MIN;
    }

    private static boolean spawnAt(ServerLevel level, BlockPos lava) {
        if (!level.isPositionEntityTicking(lava)) {
            return false;
        }
        RandomSource random = level.random;
        boolean blaze = random.nextFloat() < BLAZE_CHANCE;

        Mob mob;
        BlockPos at;
        if (blaze) {
            mob = EntityType.BLAZE.create(level);
            at = lava.above(1 + random.nextInt(3));
        } else {
            mob = EntityType.MAGMA_CUBE.create(level);
            at = lava;
        }
        if (mob == null) {
            return false;
        }

        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        // Lava has no collision box, so this passes for a magma cube sitting in it and fails for solid rock.
        if (!level.noCollision(mob)) {
            return false;
        }

        // EVENT rather than NATURAL so Infest does not double these on top of this spawner's own rate.
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        level.addFreshEntityWithPassengers(mob);
        CursesMod.LOGGER.debug("Inferno: spawned {} in a lava pool at {}", mob.getType(), at);
        return true;
    }
}
