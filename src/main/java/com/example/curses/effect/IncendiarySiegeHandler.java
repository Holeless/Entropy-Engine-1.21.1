package com.example.curses.effect;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.registry.CursesItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Incendiary, siege part: a witch that cannot walk to a nearby player (they are walled in, or up a pillar)
 * lobs Molotov Cocktails at them every few seconds, whether or not it can see them, to burn the shelter down.
 * The molotov bursts on the first wall it meets and sets fire to whatever around it can burn
 * ({@code IncendiaryHandler} does the bursting).
 *
 * <p>Server only. Separate from {@code IncendiaryHandler} so that file stays as it was.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class IncendiarySiegeHandler {
    private static final int SIEGE_CHECK = 20;       // ticks between checks per witch
    private static final int COOLDOWN = 120;         // ticks between throws
    private static final double MIN_DIST = 4.0;      // the fire must not start on the thrower

    // Server thread only.
    private static final Map<Witch, Long> READY = new WeakHashMap<>();

    private IncendiarySiegeHandler() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        READY.clear();
    }

    @SubscribeEvent
    public static void onWitchTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Witch witch) || witch.level().isClientSide()) {
            return;
        }
        if (!witch.isAlive() || witch.isDrinkingPotion() || (witch.tickCount + witch.getId()) % SIEGE_CHECK != 0) {
            return;
        }
        ServerLevel level = (ServerLevel) witch.level();
        if (!CurseData.isActive(level.getServer(), Curses.INCENDIARY)) {
            return;
        }
        long now = level.getGameTime();
        Long ready = READY.get(witch);
        if (ready != null && now < ready) {
            return;
        }

        Player victim = Siege.sealedPlayer(witch, level, MIN_DIST);
        if (victim == null) {
            return;
        }
        Vec3 aim = victim.position().add(0.0, 1.0, 0.0);
        if (Siege.lobPotion(witch, level, aim, new ItemStack(CursesItems.MOLOTOV.get()))) {
            READY.put(witch, now + COOLDOWN);
            CursesMod.LOGGER.debug("Incendiary: witch at {} threw a molotov at a sealed-in player", witch.blockPosition());
        }
    }
}
