package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Nocturne: phantoms spawn every night no matter how recently you slept, and also in caves, like bats.
 *
 * <p>This is a spawner of its own, separate from vanilla's (which still runs). Every 30-60 seconds at night,
 * each player gets a pair of phantoms: high above them if they are on the surface, or out in the dark cave
 * air around them if they are underground. Server only.</p>
 *
 * <p><b>Surface</b> means open sky, or above sea level with a roof close overhead (a house, a sky base). In that
 * case the phantoms appear above the roof, not above the player's head, so a player in a roofed sky base still
 * gets them (and the Payload siege logic takes over). A cave under more than {@link #ROOF_NEAR} blocks of rock
 * still counts as underground.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class NocturneHandler {
    private static final int MIN_INTERVAL = 600;     // ticks
    private static final int RANDOM_INTERVAL = 600;
    private static final int MAX_NEARBY = 8;         // phantom cap around a player
    private static final int ATTEMPTS = 16;
    private static final int GROUP_SIZE = 2;         // phantoms per spawn
    private static final int ROOF_NEAR = 24;         // a roof this close overhead still counts as "surface"
    private static final int BUILD_MARGIN = 20;      // keep spawns this far below the build limit

    private static int cooldown = 0;

    private NocturneHandler() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!CurseData.isActive(level.getServer(), Curses.NOCTURNE)) {
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        long time = level.getDayTime() % 24000L;
        if (time < 13000L || time > 23000L) {
            return; // night only
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }

        cooldown = MIN_INTERVAL + level.random.nextInt(RANDOM_INTERVAL);
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                spawnNear(level, player);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cooldown = 0;
    }

    private static void spawnNear(ServerLevel level, ServerPlayer player) {
        if (level.getEntitiesOfClass(Phantom.class, player.getBoundingBox().inflate(64.0)).size() >= MAX_NEARBY) {
            return;
        }

        RandomSource random = level.random;
        BlockPos origin = player.blockPosition();
        int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, origin.getX(), origin.getZ());
        // Surface = open sky, OR above sea level with a roof close overhead (a house or a sky base).
        // A cave under more than ROOF_NEAR blocks of rock still counts as underground.
        boolean surface = origin.getY() >= level.getSeaLevel()
                && (level.canSeeSky(origin) || roof - origin.getY() <= ROOF_NEAR);
        // Phantoms appear above the roof, so a roofed player at any height still gets them.
        BlockPos above = origin.atY(Math.max(origin.getY(), roof));
        int maxY = level.getMaxBuildHeight() - BUILD_MARGIN;

        BlockPos spot = null;
        for (int i = 0; i < ATTEMPTS && spot == null; i++) {
            BlockPos pos = surface ? surfaceCandidate(above, random, maxY) : caveCandidate(origin, random);
            if (pos != null && isFree(level, pos) && (surface || isDarkCave(level, pos))) {
                spot = pos;
            }
        }
        if (spot == null) {
            return;
        }

        DifficultyInstance difficulty = level.getCurrentDifficultyAt(spot);
        for (int i = 0; i < GROUP_SIZE; i++) {
            Phantom phantom = EntityType.PHANTOM.create(level);
            if (phantom == null) {
                continue;
            }
            phantom.moveTo(spot, 0.0F, 0.0F);
            phantom.finalizeSpawn(level, difficulty, MobSpawnType.NATURAL, null);
            level.addFreshEntityWithPassengers(phantom);
        }
    }

    /** Same spot vanilla picks: 20-34 blocks up, scattered sideways (never above maxY). */
    private static BlockPos surfaceCandidate(BlockPos origin, RandomSource random, int maxY) {
        BlockPos pos = origin.above(20 + random.nextInt(15))
                .east(-10 + random.nextInt(21))
                .south(-10 + random.nextInt(21));
        return pos.getY() > maxY ? pos.atY(maxY) : pos;
    }

    /** Somewhere in the cave around the player, never right on top of them. */
    private static BlockPos caveCandidate(BlockPos origin, RandomSource random) {
        BlockPos pos = origin.offset(random.nextInt(25) - 12, random.nextInt(13) - 6, random.nextInt(25) - 12);
        return pos.distSqr(origin) < 64.0 ? null : pos;
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos)
                && level.isPositionEntityTicking(pos)
                && NaturalSpawner.isValidEmptySpawnBlock(
                        level, pos, level.getBlockState(pos), level.getFluidState(pos), EntityType.PHANTOM);
    }

    private static boolean isDarkCave(ServerLevel level, BlockPos pos) {
        return !level.canSeeSky(pos) && level.getBrightness(LightLayer.BLOCK, pos) <= 7;
    }
}
