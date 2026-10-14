package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/** Insomnia: players cannot sleep. */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class InsomniaHandler {
    private InsomniaHandler() {
    }

    @SubscribeEvent
    public static void onCanSleep(CanPlayerSleepEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !CurseData.isActive(server, Curses.INSOMNIA)) {
            return;
        }

        event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        player.displayClientMessage(Component.literal("You cannot sleep."), true);
    }
}
