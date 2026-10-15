package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Sunproof: mobs do not catch fire in sunlight.
 *
 * <p>Zombies, skeletons, phantoms and every other mob that burns by day decide to do so by asking
 * {@code Mob.isSunBurnTick()}. While the curse is on, {@code MobMixin} makes that answer "no", so nothing is set
 * alight by the sun. Fire from anything else (lava, flames, a player) is untouched, and mobs that are already
 * burning just burn out normally.</p>
 *
 * <p>This class only keeps a cached "is the curse on" flag (refreshed once a second) so the mixin, which runs for
 * every mob every tick, does not have to look the curse up each time.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class SunproofHandler {
    private static volatile boolean active;

    private SunproofHandler() {
    }

    /** Is Sunproof active right now? Used by {@code MobMixin}. */
    public static boolean isActive() {
        return active;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 == 0) {
            active = CurseData.isActive(server, Curses.SUNPROOF);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        active = false;
    }
}
