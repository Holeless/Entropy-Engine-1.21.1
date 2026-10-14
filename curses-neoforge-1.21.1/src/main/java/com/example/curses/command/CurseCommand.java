package com.example.curses.command;

import java.util.List;

import com.example.curses.curse.Curse;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /curses list | add <curse> | remove <curse>} (operators only).
 *
 * <p>The create-world screen is the normal way to pick curses; this command exists for dedicated servers
 * (which have no such screen) and for testing.</p>
 */
public final class CurseCommand {
    private static final SuggestionProvider<CommandSourceStack> CURSE_NAMES = (context, builder) ->
            SharedSuggestionProvider.suggest(Curses.all().stream().map(curse -> curse.id().getPath()), builder);

    private CurseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("curses")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(CurseCommand::list))
                .then(Commands.literal("add")
                        .then(Commands.argument("curse", StringArgumentType.word())
                                .suggests(CURSE_NAMES)
                                .executes(context -> set(context, true))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("curse", StringArgumentType.word())
                                .suggests(CURSE_NAMES)
                                .executes(context -> set(context, false)))));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<Curse> active = CurseData.get(source.getServer()).activeCurses();
        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("curses.command.none"), false);
            return 0;
        }
        MutableComponent names = Component.empty();
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(active.get(i).name());
        }
        source.sendSuccess(() -> Component.translatable("curses.command.list", names), false);
        return active.size();
    }

    private static int set(CommandContext<CommandSourceStack> context, boolean on) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "curse");
        Curse curse = Curses.byName(name);
        if (curse == null) {
            source.sendFailure(Component.translatable("curses.command.unknown", name));
            return 0;
        }
        boolean changed = CurseData.get(source.getServer()).setActive(curse, on);
        if (!changed) {
            source.sendFailure(Component.translatable(on ? "curses.command.already_active" : "curses.command.not_active", curse.name()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(on ? "curses.command.added" : "curses.command.removed", curse.name()), true);
        return 1;
    }
}
