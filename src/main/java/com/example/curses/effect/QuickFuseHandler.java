package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Quick Fuse: creepers explode 50% faster.
 *
 * <p>A creeper's fuse is the counter {@code swell}, which goes up by 1 every tick while it is swelling and
 * explodes at {@code maxSwell} (30 ticks). While Quick Fuse is active the counter gets one extra point on
 * every second tick, so it advances 1.5 points per tick on average and the fuse lasts 20 ticks instead of 30.
 * The extra point is added after the creeper's own tick, so vanilla still does the actual exploding
 * (with its normal radius, charged-creeper power and so on) on the next tick.</p>
 *
 * <p>Server only. Creepers on the client keep swelling at the normal rate, so the swell animation is a
 * little behind the fuse.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class QuickFuseHandler {
    private QuickFuseHandler() {
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Creeper creeper) || creeper.level().isClientSide()) {
            return;
        }
        // Only creepers that are actually counting down; this also keeps the curse lookup off the hot path
        if (creeper.getSwellDir() <= 0 || creeper.swell <= 0 || creeper.swell >= creeper.maxSwell) {
            return;
        }
        if ((creeper.tickCount & 1) != 0) {
            return;
        }
        MinecraftServer server = creeper.getServer();
        if (server == null || !CurseData.isActive(server, Curses.QUICK_FUSE)) {
            return;
        }
        creeper.swell = Math.min(creeper.swell + 1, creeper.maxSwell);
    }
}
