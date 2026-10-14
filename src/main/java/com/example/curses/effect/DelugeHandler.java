package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Deluge: increased chance of rain and thunderstorms.
 *
 * <p>Vanilla weather is two countdowns on the overworld: "time until it starts raining" and "time until
 * thunder starts". When a countdown reaches zero the weather flips and a new countdown is picked.
 * While Deluge is active the dry-spell countdowns run {@link #SPEED} times as fast, so rain starts about
 * that many times as often, and once it is raining the thunder countdown runs that fast too, so rain turns
 * into thunderstorms much sooner. How long a spell of rain or thunder lasts is left as in vanilla.</p>
 *
 * <p>Respects the {@code doWeatherCycle} game rule and {@code /weather clear} (vanilla holds the countdowns
 * while the clear-weather timer is running, and so does this). Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class DelugeHandler {
    /** Dry-spell countdowns run this many times as fast as in vanilla. */
    public static final int SPEED = 3;

    private static final int EXTRA_PER_TICK = SPEED - 1;

    private DelugeHandler() {
    }

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) {
            return;
        }
        if (!(level.getLevelData() instanceof ServerLevelData data) || data.getClearWeatherTime() > 0) {
            return;
        }
        if (!CurseData.isActive(level.getServer(), Curses.DELUGE)) {
            return;
        }

        // Wait for rain: count down faster, but stop at 1 so vanilla itself does the flip to raining
        if (!data.isRaining() && data.getRainTime() > 1) {
            data.setRainTime(Math.max(1, data.getRainTime() - EXTRA_PER_TICK));
        }
        // Raining: wait for thunder faster
        if (data.isRaining() && !data.isThundering() && data.getThunderTime() > 1) {
            data.setThunderTime(Math.max(1, data.getThunderTime() - EXTRA_PER_TICK));
        }
    }
}
