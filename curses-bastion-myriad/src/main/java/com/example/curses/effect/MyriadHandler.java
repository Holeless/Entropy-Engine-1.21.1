package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Myriad: hostile mobs spawn twice as often (+100%).
 *
 * <p>Two parts, both needed because vanilla limits spawning in two ways (how often it tries, and a cap on
 * how many hostile mobs may exist around a player):</p>
 * <ul>
 *   <li><b>Rate</b>: every time the game naturally spawns a hostile mob (this includes each member of a pack),
 *       a second one of the same kind is spawned right next to it, using the same placement rules as
 *       vanilla (light level, floor, free space, not within 24 blocks of a player or the world spawn). If no
 *       valid spot is found after a few tries that one is skipped.</li>
 *   <li><b>Cap</b>: the hostile mob cap is doubled while the curse is active (see {@code MobCategoryMixin}), so
 *       the extra mobs are not just culled by the limit in the same moment.</li>
 * </ul>
 *
 * <p>Only natural spawns are multiplied, not spawners, spawn eggs, structures, raids or commands. Server
 * only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class MyriadHandler {
    /** Hostile spawning is multiplied by this (2 = +100%). Also the factor the mob cap is multiplied by. */
    public static final int MULTIPLIER = 2;

    private static final int PLACE_ATTEMPTS = 8;
    private static final int SPREAD = 3;                      // extra mob appears within this many blocks
    private static final double PLAYER_DISTANCE = 24.0;       // same as vanilla's natural spawning

    /** Read by the mob cap mixin on the server thread. */
    private static volatile boolean capMultiplied;
    /** True while an extra mob is being spawned, so that spawn does not multiply again. */
    private static boolean spawningExtra;

    private MyriadHandler() {
    }

    /** Is the hostile mob cap currently raised? Used by {@code MobCategoryMixin}. */
    public static boolean isCapMultiplied() {
        return capMultiplied;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 0) {
            capMultiplied = CurseData.isActive(server, Curses.MYRIAD);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        capMultiplied = false;
        spawningExtra = false;
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (spawningExtra || event.getSpawnType() != MobSpawnType.NATURAL) {
            return;
        }
        Mob original = event.getEntity();
        if (original.getType().getCategory() != MobCategory.MONSTER) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        if (!CurseData.isActive(level.getServer(), Curses.MYRIAD)) {
            return;
        }
        for (int extra = 1; extra < MULTIPLIER; extra++) {
            spawnExtra(level, original, event.getX(), event.getY(), event.getZ());
        }
    }

    /** Spawns one more mob like {@code original} near (x, y, z), if a valid spot turns up. */
    private static void spawnExtra(ServerLevel level, Mob original, double x, double y, double z) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < PLACE_ATTEMPTS; i++) {
            double px = Math.floor(x) + 0.5 + random.nextIntBetweenInclusive(-SPREAD, SPREAD);
            double pz = Math.floor(z) + 0.5 + random.nextIntBetweenInclusive(-SPREAD, SPREAD);
            double py = Math.floor(y) + random.nextIntBetweenInclusive(-1, 1);
            BlockPos pos = BlockPos.containing(px, py, pz);

            if (!farFromPlayers(level, px, py, pz)
                    || !SpawnPlacements.isSpawnPositionOk(original.getType(), level, pos)
                    || !SpawnPlacements.checkSpawnRules(original.getType(), level, MobSpawnType.NATURAL, pos, random)) {
                continue;
            }
            Entity created = original.getType().create(level);
            if (!(created instanceof Mob extra)) {
                return;
            }
            extra.moveTo(px, py, pz, random.nextFloat() * 360.0F, 0.0F);
            if (!extra.checkSpawnRules(level, MobSpawnType.NATURAL) || !extra.checkSpawnObstruction(level)) {
                continue;
            }

            spawningExtra = true;
            try {
                extra.finalizeSpawn(level, level.getCurrentDifficultyAt(extra.blockPosition()), MobSpawnType.NATURAL, null);
                level.addFreshEntityWithPassengers(extra);
            } finally {
                spawningExtra = false;
            }
            return;
        }
    }

    /** Vanilla never naturally spawns hostile mobs within 24 blocks of a player or of the world spawn. */
    private static boolean farFromPlayers(ServerLevel level, double x, double y, double z) {
        Player nearest = level.getNearestPlayer(x, y, z, -1.0, false);
        if (nearest != null && nearest.distanceToSqr(x, y, z) <= PLAYER_DISTANCE * PLAYER_DISTANCE) {
            return false;
        }
        return !level.getSharedSpawnPos().closerToCenterThan(new Vec3(x, y, z), PLAYER_DISTANCE);
    }
}
