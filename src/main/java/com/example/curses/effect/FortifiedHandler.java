package com.example.curses.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.curse.PendingCurses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Fortified: villages spawn with 3 Iron Golems, and Iron Golems take cover when the player they are hunting
 * cannot be reached (see {@link TakeCoverGoal}).
 *
 * <p><b>Village golems:</b> when a village is generated, a few ticks later (spawning during chunk loading is
 * not safe) the golems are placed in the chunk the village starts in, which is where its town centre is. They
 * prefer flat open ground (streets, the square around the bell) over roofs. Golems that are already standing
 * near the village count toward the 3, so a village is topped up to 3 rather than getting 3 more.
 * Only freshly generated villages are affected, never villages that already exist in a world, and the
 * golems are persistent (they never despawn).</p>
 *
 * <p><b>Taking cover:</b> {@link TakeCoverGoal} is added to every Iron Golem when it joins the world.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class FortifiedHandler {
    /** Iron golems a new village is topped up to. */
    public static final int VILLAGE_GOLEMS = 3;

    private static final int SPAWN_DELAY = 20;     // ticks after the chunk loads
    private static final int GIVE_UP = 1200;       // ticks to wait for the chunk to be ready
    private static final double EXISTING_RANGE = 32.0;
    private static final int MIN_SPACING_SQR = 4 * 4;

    private record Pending(ServerLevel level, ChunkPos chunk, BlockPos center, BoundingBox box, long due) {
    }

    // Server thread only.
    private static final List<Pending> PENDING = new ArrayList<>();

    private FortifiedHandler() {
    }

    /** Is the Fortified curse active in the world this level belongs to? */
    static boolean enabled(Level level) {
        return level instanceof ServerLevel serverLevel && CurseData.isActive(serverLevel.getServer(), Curses.FORTIFIED);
    }

    /**
     * Like {@link #enabled} but also right while the world is first being created: the spawn area is generated
     * before the create-world selection has been moved into the world's saved curses.
     */
    private static boolean activeNow(ServerLevel level) {
        MinecraftServer server = level.getServer();
        CurseData data = CurseData.get(server);
        if (data.isInitialized()) {
            return data.isActive(Curses.FORTIFIED);
        }
        return !server.isDedicatedServer() && PendingCurses.isSelected(Curses.FORTIFIED);
    }

    // ---------------------------------------------------------------------------------------------------
    // Cover AI
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof IronGolem golem)) {
            return;
        }
        for (WrappedGoal wrapped : golem.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof TakeCoverGoal) {
                return;
            }
        }
        // Priority 0 beats the golem's own movement goals, which share the MOVE flag.
        golem.goalSelector.addGoal(0, new TakeCoverGoal(golem));
    }

    // ---------------------------------------------------------------------------------------------------
    // Village golems
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) {
            return;
        }

        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            // Only the chunk the village starts in handles it, so each village is seen once.
            if (!start.isValid() || start.getPieces().isEmpty() || !start.getChunkPos().equals(chunk.getPos())) {
                continue;
            }
            if (!registry.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) {
                continue;
            }
            if (!activeNow(level)) {
                return;
            }
            BoundingBox box = start.getPieces().get(0).getBoundingBox(); // the town centre
            PENDING.add(new Pending(level, chunk.getPos(), box.getCenter(), box, level.getGameTime() + SPAWN_DELAY));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || PENDING.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            if (pending.level() != level || now < pending.due()) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(pending.chunk().x, pending.chunk().z) == null) {
                if (now > pending.due() + GIVE_UP) {
                    it.remove();
                }
                continue;
            }
            it.remove();
            spawnGolems(level, pending);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void spawnGolems(ServerLevel level, Pending pending) {
        AABB area = AABB.of(pending.box()).inflate(EXISTING_RANGE);
        int existing = level.getEntitiesOfClass(IronGolem.class, area).size();
        int needed = VILLAGE_GOLEMS - existing;
        if (needed <= 0) {
            return;
        }

        List<BlockPos> spots = pickSpots(level, pending, needed);
        for (BlockPos spot : spots) {
            IronGolem golem = EntityType.IRON_GOLEM.create(level);
            if (golem == null) {
                continue;
            }
            golem.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
            golem.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.STRUCTURE, null);
            golem.setPersistenceRequired();
            level.addFreshEntityWithPassengers(golem);
        }
        CursesMod.LOGGER.debug("Fortified: village at {} got {} iron golem(s) ({} already there)",
                pending.center(), spots.size(), existing);
    }

    /**
     * Spots for the golems inside the village's start chunk, nearest the town centre first. The first pass
     * only takes flat open ground that is not a roof; the second takes anything a golem can stand on.
     * Columns on the chunk's outer ring are skipped so neighbouring chunks are never read (they may not exist).
     */
    private static List<BlockPos> pickSpots(ServerLevel level, Pending pending, int count) {
        int minX = pending.chunk().getMinBlockX();
        int minZ = pending.chunk().getMinBlockZ();
        BlockPos center = pending.center();
        int cx = Math.max(minX + 1, Math.min(minX + 14, center.getX()));
        int cz = Math.max(minZ + 1, Math.min(minZ + 14, center.getZ()));

        List<BlockPos> columns = new ArrayList<>();
        for (int x = minX + 1; x <= minX + 14; x++) {
            for (int z = minZ + 1; z <= minZ + 14; z++) {
                columns.add(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z));
            }
        }
        columns.sort(Comparator.comparingInt(pos -> (pos.getX() - cx) * (pos.getX() - cx) + (pos.getZ() - cz) * (pos.getZ() - cz)));

        List<BlockPos> result = new ArrayList<>();
        for (int pass = 0; pass < 2 && result.size() < count; pass++) {
            for (BlockPos pos : columns) {
                if (result.size() >= count) {
                    break;
                }
                if (standable(level, pos, pass == 0) && farFromChosen(result, pos)) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    private static boolean farFromChosen(List<BlockPos> chosen, BlockPos pos) {
        for (BlockPos other : chosen) {
            int dx = other.getX() - pos.getX();
            int dz = other.getZ() - pos.getZ();
            if (dx * dx + dz * dz < MIN_SPACING_SQR) {
                return false;
            }
        }
        return true;
    }

    private static boolean standable(ServerLevel level, BlockPos pos, boolean strict) {
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.getFluidState().isEmpty() || !floor.isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            BlockPos at = pos.above(i);
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty() || !level.getFluidState(at).isEmpty()) {
                return false;
            }
        }
        if (!strict) {
            return true;
        }

        // Roofs are made of these; streets and village squares are not.
        if (floor.is(BlockTags.PLANKS) || floor.is(BlockTags.STAIRS) || floor.is(BlockTags.SLABS)
                || floor.is(BlockTags.LOGS) || floor.is(BlockTags.WOOL)) {
            return false;
        }
        // Flat: all eight neighbouring columns top out at the same height.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX() + dx, pos.getZ() + dz) != pos.getY()) {
                    return false;
                }
            }
        }
        return true;
    }
}
