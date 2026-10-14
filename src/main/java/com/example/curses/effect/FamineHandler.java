package com.example.curses.effect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Famine: hunger depletes 50% faster.
 *
 * <p>Works by making every point of exhaustion count 1.5 times (see {@link ExhaustionTracker}).
 * Runs on the server only; the client just displays the resulting food level.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class FamineHandler {
    /** Hunger depletes this many times as fast while Famine is active. */
    public static final float HUNGER_MULTIPLIER = 1.5F;

    private static final float EXTRA_FACTOR = HUNGER_MULTIPLIER - 1.0F;

    // Server thread only.
    private static final Map<UUID, ExhaustionTracker> TRACKERS = new HashMap<>();

    private FamineHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        FoodData food = player.getFoodData();
        ExhaustionTracker tracker = TRACKERS.computeIfAbsent(player.getUUID(), id -> new ExhaustionTracker(food.getExhaustionLevel()));

        float extra = tracker.extraBeforeTick(food.getExhaustionLevel(), extraFactor(player));
        if (extra > 0.0F) {
            food.addExhaustion(extra);
        }
        tracker.tickStarted(food.getExhaustionLevel());
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ExhaustionTracker tracker = TRACKERS.get(player.getUUID());
        if (tracker == null) {
            return;
        }
        FoodData food = player.getFoodData();

        float extra = tracker.extraAfterTick(food.getExhaustionLevel(), extraFactor(player));
        if (extra > 0.0F) {
            food.addExhaustion(extra);
        }
        tracker.tickEnded(food.getExhaustionLevel());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TRACKERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKERS.clear();
    }

    /** 0.5 while Famine is active in this world, otherwise 0 (the tracker then just stays in sync). */
    private static float extraFactor(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null && CurseData.isActive(server, Curses.FAMINE)) {
            return EXTRA_FACTOR;
        }
        return 0.0F;
    }
}
