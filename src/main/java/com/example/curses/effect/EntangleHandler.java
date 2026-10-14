package com.example.curses.effect;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

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
 * <p>Webs only replace air or replaceable blocks (grass, snow). Webs are permanent: they never dissolve and
 * stay until a player breaks them.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class EntangleHandler {
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

    /** Puts a permanent cobweb at pos if that spot is free. Returns true if it did. */
    private static boolean placeWeb(ServerLevel level, BlockPos pos) {
        BlockState there = level.getBlockState(pos);
        boolean free = there.isAir() || (there.canBeReplaced() && there.getFluidState().isEmpty());
        if (!free) {
            return false;
        }

        BlockState web = Blocks.COBWEB.defaultBlockState();
        level.setBlockAndUpdate(pos, web);
        level.playSound(null, pos, web.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        return true;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LAST_SNARE.clear();
        LAST_WAKE.clear();
        LAST_SNARED.clear();
    }
}
