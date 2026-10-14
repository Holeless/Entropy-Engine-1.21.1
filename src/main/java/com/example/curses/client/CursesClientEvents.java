package com.example.curses.client;

import com.example.curses.CursesMod;
import com.example.curses.curse.PendingCurses;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Adds the "Curses" button to the create-world screen and keeps the pending selection from going stale. */
@EventBusSubscriber(value = Dist.CLIENT, modid = CursesMod.MOD_ID)
public final class CursesClientEvents {
    private static final int BUTTON_WIDTH = 100;
    private static final int MIN_BUTTON_WIDTH = 40;
    // The create-world footer holds two 150px buttons with an 8px gap: 308px wide, centred.
    private static final int FOOTER_HALF_WIDTH = 154;
    private static final int GAP = 8;

    private CursesClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) {
            return;
        }

        // Next to the Create / Cancel buttons, on whichever side has more room
        int centre = screen.width / 2;
        int rightX = centre + FOOTER_HALF_WIDTH + GAP;
        int rightSpace = screen.width - rightX - 6;
        int leftSpace = centre - FOOTER_HALF_WIDTH - GAP - 6;

        int x;
        int width;
        if (rightSpace >= leftSpace) {
            width = Math.max(MIN_BUTTON_WIDTH, Math.min(BUTTON_WIDTH, rightSpace));
            x = rightX;
        } else {
            width = Math.max(MIN_BUTTON_WIDTH, Math.min(BUTTON_WIDTH, leftSpace));
            x = centre - FOOTER_HALF_WIDTH - GAP - width;
        }

        Button button = Button.builder(
                        Component.translatable("curses.create.button", PendingCurses.count()),
                        pressed -> Minecraft.getInstance().setScreen(new CurseSelectionScreen(screen)))
                .bounds(x, screen.height - 27, width, 20)
                .tooltip(Tooltip.create(Component.translatable("curses.create.tooltip")))
                .build();
        event.addListener(button);
    }

    /**
     * Back on the title or world-select screen means no world is being created any more (cancelled, or the
     * world was made and the player has since left it), so drop any selection that was never used.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next instanceof TitleScreen || next instanceof SelectWorldScreen) {
            PendingCurses.clear();
        }
    }
}
