package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Coven: witches spawn far more often, at night and in caves.
 *
 * <p>Vanilla barely spawns witches (a tiny share of the hostile spawns, mostly swamp huts). This is a spawner of its
 * own, separate from vanilla's (which still runs). Every 10-20 seconds each player gets one try: it looks for a spot
 * {@link #MIN_DIST}-{@link #MAX_DIST} blocks away that is dark enough for a hostile mob (the same test vanilla uses, so
 * that means night in the open, and dark caves at any time of day) and puts a witch there. On the surface the spot is
 * on the ground; if the player is under a roof or underground it is in the cave air around them. No more than
 * {@link #MAX_NEARBY} witches are kept near a player. They despawn like any other hostile mob.</p>
 *
 * <p>Only loaded, entity-ticking chunks are touched. Respects peaceful difficulty and the {@code doMobSpawning} game
 * rule. Overworld and server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class CovenHandler {
    private static final int MIN_INTERVAL = 200;       // ticks
    private static final int RANDOM_INTERVAL = 200;
    private static final int MAX_NEARBY = 4;           // witches within 96 blocks of a player
    private static final int ATTEMPTS = 24;
    private static final int MIN_DIST = 24;            // same minimum distance vanilla keeps from players
    private static final int MAX_DIST = 44;
    private static final int CAVE_UP = 6;              // underground: look this far above the player's feet...
    private static final int CAVE_DOWN = 10;           // ... and this far below

    private static int cooldown = 0;

    private CovenHandler() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!CurseData.isActive(level.getServer(), Curses.COVEN)) {
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
        if (level.getEntitiesOfClass(Witch.class, player.getBoundingBox().inflate(96.0)).size() >= MAX_NEARBY) {
            return;
        }

        RandomSource random = level.random;
        BlockPos origin = player.blockPosition();
        boolean open = level.canSeeSky(origin);
        for (int i = 0; i < ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = MIN_DIST + random.nextDouble() * (MAX_DIST - MIN_DIST);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
                continue;
            }
            BlockPos spot = open ? surfaceSpot(level, x, z) : caveSpot(level, x, origin.getY(), z);
            if (spot != null && spawnAt(level, spot)) {
                return;
            }
        }
    }

    /** The first free block above the ground in this column. */
    private static BlockPos surfaceSpot(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** A spot in this column near the player's height with a solid floor and two blocks of air, or null. */
    private static BlockPos caveSpot(ServerLevel level, int x, int originY, int z) {
        for (int dy = CAVE_UP; dy >= -CAVE_DOWN; dy--) {
            BlockPos pos = new BlockPos(x, originY + dy, z);
            if (level.isOutsideBuildHeight(pos)) {
                continue;
            }
            BlockPos below = pos.below();
            if (level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                    && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    private static boolean spawnAt(ServerLevel level, BlockPos pos) {
        if (!level.isPositionEntityTicking(pos)
                || level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, MIN_DIST - 1, false) != null) {
            return false;
        }
        // Same rules as a hostile mob spawning naturally: a valid floor, and dark enough (night, or a cave).
        if (!SpawnPlacements.isSpawnPositionOk(EntityType.WITCH, level, pos)
                || !Monster.isDarkEnoughToSpawn(level, pos, level.random)) {
            return false;
        }
        Witch witch = EntityType.WITCH.create(level);
        if (witch == null) {
            return false;
        }
        witch.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        if (!witch.checkSpawnObstruction(level)) {
            return false;
        }

        // EVENT rather than NATURAL so Infest does not double these on top of this spawner's own rate.
        witch.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        level.addFreshEntityWithPassengers(witch);
        CursesMod.LOGGER.debug("Coven: spawned a witch at {}", pos);
        return true;
    }
}
