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

/**
 * Hooks the create-world screen: adds the "Curses..." button under "Allow Cheats" (Game tab) and the row of
 * chosen-curse icons below it. All layout and drawing lives in {@link CursePanel}.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = CursesMod.MOD_ID)
public final class CursesClientEvents {
    private CursesClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) {
            return;
        }

        // Position, size, label and tooltip are set by CursePanel.layout every frame it changes; the button is
        // hidden whenever the Game tab is not the open tab.
        Button button = Button.builder(
                        Component.translatable("curses.create.button", PendingCurses.count()),
                        pressed -> Minecraft.getInstance().setScreen(new CurseSelectionScreen(screen)))
                .bounds(0, 0, 210, 20)
                .tooltip(Tooltip.create(Component.translatable("curses.create.tooltip")))
                .build();
        button.visible = false;
        CursePanel.attach(screen, button);
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {
            CursePanel.layout(screen);
        }
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof CreateWorldScreen) {
            CursePanel.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
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
            CursePanel.detach();
        }
    }
}
