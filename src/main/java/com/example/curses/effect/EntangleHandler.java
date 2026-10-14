package com.example.curses.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Entangle: spiders (cave spiders too, they are Spiders) leave webs in their wake and try to catch players
 * with them.
 *
 * <ul>
 *   <li><b>Snare</b>: a spider that gets close to its target spins a cobweb onto the player's feet. Reach
 *       scales with the spider's size (a normal spider about 1 block away, a cave spider about 0.75). It
 *       checks 5 times a second and each check has a 40% chance, so being next to one for a second or two is
 *       almost always enough, but not guaranteed. Needs line of sight, at most one snare per spider every
 *       2 seconds.</li>
 *   <li><b>Wake</b>: while moving, a spider now and then (about once every 5 seconds of walking or climbing)
 *       drops a cobweb where it is standing.</li>
 * </ul>
 *
 * <p>Webs only replace air or replaceable blocks (grass, snow), and dissolve after 30 seconds. Webs still
 * standing when the world is closed are removed, so none are left behind permanently.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class EntangleHandler {
    private static final int CHECK_INTERVAL = 4;      // ticks between checks
    private static final float SNARE_CHANCE = 0.4F;   // per check, while a target is in reach
    private static final int SNARE_COOLDOWN = 40;     // ticks
    private static final float WAKE_CHANCE = 0.04F;   // per check, while moving
    private static final int WAKE_COOLDOWN = 60;      // ticks
    private static final int WEB_LIFETIME = 600;      // ticks
    private static final int MAX_WEBS = 400;          // safety cap on webs alive at once

    private record Web(ResourceKey<Level> dimension, BlockPos pos, long expires) {
    }

    // Server thread only.
    private static final Map<Mob, Long> LAST_SNARE = new WeakHashMap<>();
    private static final Map<Mob, Long> LAST_WAKE = new WeakHashMap<>();
    private static final List<Web> WEBS = new ArrayList<>();

    private EntangleHandler() {
    }

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

    /** Webs the spider's target if it is close enough. Returns true if a web was placed. */
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
        if (spider.getRandom().nextFloat() >= SNARE_CHANCE) {
            return false;
        }
        if (!placeWeb(level, player.blockPosition(), now)) {
            return false;
        }
        LAST_SNARE.put(spider, now);
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
        if (placeWeb(level, spider.blockPosition(), now)) {
            LAST_WAKE.put(spider, now);
        }
    }

    /** Puts a cobweb at pos if that spot is free. Returns true if it did. */
    private static boolean placeWeb(ServerLevel level, BlockPos pos, long now) {
        if (WEBS.size() >= MAX_WEBS) {
            return false;
        }
        BlockState there = level.getBlockState(pos);
        boolean free = there.isAir() || (there.canBeReplaced() && there.getFluidState().isEmpty());
        if (!free) {
            return false;
        }

        BlockState web = Blocks.COBWEB.defaultBlockState();
        level.setBlockAndUpdate(pos, web);
        level.playSound(null, pos, web.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        WEBS.add(new Web(level.dimension(), pos.immutable(), now + WEB_LIFETIME));
        return true;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || WEBS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        WEBS.removeIf(web -> {
            if (web.dimension() != level.dimension() || now < web.expires()) {
                return false;
            }
            if (!level.hasChunkAt(web.pos())) {
                return false; // wait until the chunk is loaded again
            }
            if (level.getBlockState(web.pos()).is(Blocks.COBWEB)) {
                level.removeBlock(web.pos(), false);
            }
            return true;
        });
    }

    /** Clean up before the world closes so no web outlives its timer. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Web web : WEBS) {
            ServerLevel level = event.getServer().getLevel(web.dimension());
            if (level != null && level.hasChunkAt(web.pos()) && level.getBlockState(web.pos()).is(Blocks.COBWEB)) {
                level.removeBlock(web.pos(), false);
            }
        }
        WEBS.clear();
        LAST_SNARE.clear();
        LAST_WAKE.clear();
    }
}
