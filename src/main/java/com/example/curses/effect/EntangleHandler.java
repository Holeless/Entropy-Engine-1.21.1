package com.example.curses.effect;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Entangle: spiders (cave spiders too, they are Spiders) leave webs in their wake and sometimes catch players
 * with them.
 *
 * <ul>
 *   <li><b>Snare</b>: a spider that gets close to its target may spin a cobweb onto the player's feet. Reach
 *       scales with the spider's size (a normal spider about 1 block away, a cave spider about 0.75). It
 *       checks 5 times a second with a 20% chance each and needs line of sight. To keep a player from being
 *       webbed over and over: one snare per spider every 8 seconds, and one snare per player every 15 seconds
 *       (whichever spider).</li>
 *   <li><b>Wake</b>: while moving, a spider now and then (about once every 5 seconds of walking or climbing)
 *       drops a cobweb where it is standing.</li>
 * </ul>
 *
 * <p>Webs only replace air or replaceable blocks (grass, snow). Every web a spider places disappears by itself
 * exactly {@link #WEB_LIFETIME} ticks (30 seconds) after it was placed. It is removed without being "broken", so
 * it never drops string. The expiry times are saved with the world ({@code data/curses_webs.dat}), so webs
 * still vanish on time across restarts, and a web in a chunk that is not loaded at that moment is removed as soon
 * as the chunk loads. A web a player breaks earlier is simply forgotten.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class EntangleHandler {
    /** How long a spider's web lasts: 30 seconds. */
    public static final int WEB_LIFETIME = 30 * 20;

    private static final int CHECK_INTERVAL = 4;      // ticks between checks
    private static final float SNARE_CHANCE = 0.2F;   // per check, while a target is in reach
    private static final int SNARE_COOLDOWN = 160;    // ticks, per spider
    private static final int PLAYER_GRACE = 300;      // ticks, per player: no new snare this soon after one
    private static final float WAKE_CHANCE = 0.04F;   // per check, while moving
    private static final int WAKE_COOLDOWN = 60;      // ticks

    // Server thread only.
    private static final Map<Mob, Long> LAST_SNARE = new WeakHashMap<>();
    private static final Map<Mob, Long> LAST_WAKE = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_SNARED = new WeakHashMap<>();

    private EntangleHandler() {
    }

    // ---------------------------------------------------------------------------------------------------
    // Saved list of webs that are about to expire
    // ---------------------------------------------------------------------------------------------------

    /** Per dimension: position of every spider web still to be removed, and the game time it is due. */
    private static final class WebData extends SavedData {
        private static final String FILE_NAME = "curses_webs";
        private static final String KEY_POS = "Pos";
        private static final String KEY_DUE = "Due";

        private final Long2LongOpenHashMap due = new Long2LongOpenHashMap();

        static WebData get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(WebData::new, WebData::load, DataFixTypes.LEVEL), FILE_NAME);
        }

        private static WebData load(CompoundTag tag, HolderLookup.Provider registries) {
            WebData data = new WebData();
            long[] positions = tag.getLongArray(KEY_POS);
            long[] times = tag.getLongArray(KEY_DUE);
            int count = Math.min(positions.length, times.length);
            for (int i = 0; i < count; i++) {
                data.due.put(positions[i], times[i]);
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            long[] positions = new long[due.size()];
            long[] times = new long[due.size()];
            int i = 0;
            for (Long2LongMap.Entry entry : due.long2LongEntrySet()) {
                positions[i] = entry.getLongKey();
                times[i] = entry.getLongValue();
                i++;
            }
            tag.putLongArray(KEY_POS, positions);
            tag.putLongArray(KEY_DUE, times);
            return tag;
        }

        void add(BlockPos pos, long dueTime) {
            due.put(pos.asLong(), dueTime);
            setDirty();
        }
    }

    /** Removes every web whose time is up. Runs in every dimension, whether or not the curse is still active. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        WebData data = WebData.get(level);
        if (data.due.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        LongArrayList expired = null;
        for (Long2LongMap.Entry entry : data.due.long2LongEntrySet()) {
            if (entry.getLongValue() <= now) {
                if (expired == null) {
                    expired = new LongArrayList();
                }
                expired.add(entry.getLongKey());
            }
        }
        if (expired == null) {
            return;
        }

        // Removed after the loop: changing blocks must not happen while the map is being iterated.
        boolean changed = false;
        for (int i = 0; i < expired.size(); i++) {
            long key = expired.getLong(i);
            BlockPos pos = BlockPos.of(key);
            if (!level.hasChunkAt(pos)) {
                continue; // not loaded: it goes as soon as the chunk is
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.COBWEB)) {
                // setBlock, not destroyBlock: nothing is dropped.
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            data.due.remove(key);
            changed = true;
        }
        if (changed) {
            data.setDirty();
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Spiders
    // ---------------------------------------------------------------------------------------------------

    /** Centre-to-centre distance: spider 2.2 blocks, cave spider 1.5. */
    private static double triggerDistance(Spider spider) {
        return 0.8 + spider.getBbWidth();
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Spider spider) || spider.level().isClientSide()) {
            return;
        }
        if (spider.tickCount % CHECK_INTERVAL != 0 || !spider.isAlive()) {
            return;
        }
        ServerLevel level = (ServerLevel) spider.level();
        if (!CurseData.isActive(level.getServer(), Curses.ENTANGLE)) {
            return;
        }

        long now = level.getGameTime();
        if (!trySnare(spider, level, now)) {
            tryWake(spider, level, now);
        }
    }

    /** Webs the spider's target if it is close enough and the limits allow it. Returns true if a web was placed. */
    private static boolean trySnare(Spider spider, ServerLevel level, long now) {
        if (!(spider.getTarget() instanceof ServerPlayer player) || !player.isAlive()) {
            return false;
        }
        if (player.isCreative() || player.isSpectator()) {
            return false;
        }

        double reach = triggerDistance(spider);
        if (spider.distanceToSqr(player) > reach * reach || !spider.getSensing().hasLineOfSight(player)) {
            return false;
        }
        Long last = LAST_SNARE.get(spider);
        if (last != null && now - last < SNARE_COOLDOWN) {
            return false;
        }
        Long lastOnPlayer = LAST_SNARED.get(player);
        if (lastOnPlayer != null && now - lastOnPlayer < PLAYER_GRACE) {
            return false;
        }
        if (spider.getRandom().nextFloat() >= SNARE_CHANCE) {
            return false;
        }
        if (!placeWeb(level, player.blockPosition())) {
            return false;
        }
        LAST_SNARE.put(spider, now);
        LAST_SNARED.put(player, now);
        return true;
    }

    /** Sometimes drops a web where the spider is, but only while it is on the move. */
    private static void tryWake(Spider spider, ServerLevel level, long now) {
        Vec3 motion = spider.getDeltaMovement();
        boolean walking = motion.horizontalDistanceSqr() > 0.001;
        boolean climbing = spider.onClimbable() && Math.abs(motion.y) > 0.05;
        if (!walking && !climbing) {
            return;
        }
        Long last = LAST_WAKE.get(spider);
        if (last != null && now - last < WAKE_COOLDOWN) {
            return;
        }
        if (spider.getRandom().nextFloat() >= WAKE_CHANCE) {
            return;
        }
        if (placeWeb(level, spider.blockPosition())) {
            LAST_WAKE.put(spider, now);
        }
    }

    /** Puts a cobweb at pos if that spot is free, and schedules its removal. Returns true if it did. */
    private static boolean
