package com.example.curses.event;

import java.util.List;

import com.example.curses.CursesMod;
import com.example.curses.command.CurseCommand;
import com.example.curses.curse.Curse;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.PendingCurses;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Server-side glue: applies the create-world selection, announces curses on join, registers /curses. */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        CurseData data = CurseData.get(server);

        // The first time a world is loaded with this mod, take over the selection from the create-world
        // screen. Dedicated servers have no such screen, so they start uncursed (use /curses add).
        if (!data.isInitialized()) {
            if (!server.isDedicatedServer()) {
                data.replaceAll(PendingCurses.consume());
            }
            data.markInitialized();
        }

        CursesMod.LOGGER.info("Active curses: {}", data.activeCurses().stream().map(c -> c.id().toString()).toList());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        List<Curse> active = CurseData.get(player.getServer()).activeCurses();
        if (active.isEmpty()) {
            return;
        }
        MutableComponent names = Component.empty();
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(active.get(i).name());
        }
        player.sendSystemMessage(Component.translatable("curses.message.active", names).withStyle(ChatFormatting.DARK_RED));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CurseCommand.register(event.getDispatcher());
    }
}
