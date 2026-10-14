package com.example.curses.client;

import java.util.ArrayList;
import java.util.List;

import com.example.curses.curse.Curse;
import com.example.curses.curse.Curses;
import com.example.curses.curse.PendingCurses;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * The curses UI on the create-world screen (Game tab):
 *
 * <ul>
 *   <li>the "Curses" button, placed directly under "Allow Cheats" with the same width and height</li>
 *   <li>a vanilla-style box to the right of the button column that shows an icon for every chosen curse;
 *       clicking an icon shows that curse's name and description at the bottom of the box</li>
 * </ul>
 *
 * <p>Everything is positioned relative to the vanilla "Allow Cheats" button, so it follows vanilla's layout
 * at any window size / GUI scale. Client only.</p>
 */
public final class CursePanel {
    private static final ResourceLocation TEXT_FIELD = ResourceLocation.withDefaultNamespace("widget/text_field");

    private static final String CHEATS_KEY = "selectWorld.allowCommands";
    private static final int ROW_GAP = 8;        // vanilla spacing between rows of the Game tab
    private static final int BOX_GAP = 10;       // space between the button column and the box
    private static final int EDGE_MARGIN = 10;   // space kept free at the right edge of the screen
    private static final int MAX_WIDTH = 160;
    private static final int MIN_WIDTH = 66;
    private static final int PADDING = 5;
    private static final int SLOT = 22;
    private static final int SLOT_GAP = 3;
    private static final int ICON = 16;
    private static final int INFO_HEIGHT = 52;

    private static Screen owner;
    private static Button button;
    private static ResourceLocation selected;

    // Geometry of the box, refreshed every frame by layout()
    private static boolean shown;
    private static int boxX;
    private static int boxY;
    private static int boxW;
    private static int boxH;

    private CursePanel() {
    }

    public static void attach(Screen screen, Button curseButton) {
        if (owner != screen) {
            selected = null;
        }
        owner = screen;
        button = curseButton;
        shown = false;
    }

    public static void detach() {
        owner = null;
        button = null;
        selected = null;
        shown = false;
    }

    /** Places the button and the box relative to the Allow Cheats button; hides both on other tabs. */
    public static void layout(CreateWorldScreen screen) {
        shown = false;
        if (button == null || owner != screen) {
            return;
        }
        CycleButton<?> cheats = findCheatsButton(screen);
        if (cheats == null) {
            button.visible = false; // not the Game tab
            return;
        }

        int colX = cheats.getX();
        int colW = cheats.getWidth();
        int buttonY = cheats.getY() + cheats.getHeight() + ROW_GAP;

        button.setX(colX);
        button.setY(buttonY);
        button.setWidth(colW);
        button.setHeight(cheats.getHeight());
        button.visible = true;

        // The box spans the whole column of buttons, from the top-most one down to the Curses button
        int top = cheats.getY();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != button && widget.visible
                    && Math.abs(widget.getX() - colX) <= 4 && widget.getY() < top) {
                top = widget.getY();
            }
        }

        boxX = colX + colW + BOX_GAP;
        boxY = top;
        boxW = Math.min(MAX_WIDTH, screen.width - boxX - EDGE_MARGIN);
        boxH = buttonY + button.getHeight() - top;
        shown = boxW >= MIN_WIDTH;
    }

    public static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!shown) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        graphics.blitSprite(TEXT_FIELD, boxX, boxY, boxW, boxH);

        List<Curse> chosen = chosenCurses();
        int innerW = boxW - 2 * PADDING;
        int infoTop = boxY + boxH - INFO_HEIGHT;

        // Separator above the info area
        graphics.fill(boxX + PADDING, infoTop, boxX + boxW - PADDING, infoTop + 1, 0xFF555555);

        if (chosen.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.translatable("curses.panel.empty"), innerW);
            int y = boxY + PADDING + 4;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, boxX + (boxW - font.width(line)) / 2, y, 0x808080, false);
                y += font.lineHeight + 1;
            }
            return;
        }

        Curse info = selectedCurse(chosen);

        // Icons
        for (int i = 0; i < chosen.size(); i++) {
            int[] pos = slotPosition(i);
            if (pos == null) {
                break;
            }
            Curse curse = chosen.get(i);
            boolean isSelected = curse == info;
            boolean hovered = isInSlot(mouseX, mouseY, pos);
            int outline = isSelected ? 0xFFFFFFFF : hovered ? 0xFFA0A0A0 : 0xFF555555;
            graphics.fill(pos[0], pos[1], pos[0] + SLOT, pos[1] + SLOT, 0xFF000000);
            graphics.blit(curse.icon(), pos[0] + (SLOT - ICON) / 2, pos[1] + (SLOT - ICON) / 2, ICON, ICON, 0.0F, 0.0F, 16, 16, 16, 16);
            graphics.renderOutline(pos[0], pos[1], SLOT, SLOT, outline);
        }

        // Name and description of the clicked curse, at the bottom of the box
        int textX = boxX + PADDING;
        if (info == null) {
            int y = infoTop + 5;
            for (FormattedCharSequence line : font.split(Component.translatable("curses.panel.hint"), innerW)) {
                graphics.drawString(font, line, textX, y, 0x808080, false);
                y += font.lineHeight + 1;
            }
            return;
        }
        graphics.drawString(font, info.name(), textX, infoTop + 5, 0xFF5555, false);
        int y = infoTop + 5 + font.lineHeight + 3;
        int maxLines = Math.max(1, (boxY + boxH - PADDING - y) / (font.lineHeight + 1));
        List<FormattedCharSequence> lines = font.split(info.description(), innerW);
        for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
            graphics.drawString(font, lines.get(i), textX, y, 0xA0A0A0, false);
            y += font.lineHeight + 1;
        }
    }

    /** Selects the clicked icon. Returns true if the click hit an icon (so the screen should ignore it). */
    public static boolean mouseClicked(double mouseX, double mouseY) {
        if (!shown) {
            return false;
        }
        List<Curse> chosen = chosenCurses();
        for (int i = 0; i < chosen.size(); i++) {
            int[] pos = slotPosition(i);
            if (pos == null) {
                break;
            }
            if (isInSlot(mouseX, mouseY, pos)) {
                selected = chosen.get(i).id();
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        return false;
    }

    private static List<Curse> chosenCurses() {
        List<Curse> result = new ArrayList<>();
        for (Curse curse : Curses.all()) {
            if (PendingCurses.isSelected(curse)) {
                result.add(curse);
            }
        }
        return result;
    }

    /** The curse whose info is shown, or null (nothing clicked yet, or it has since been unticked). */
    private static Curse selectedCurse(List<Curse> chosen) {
        for (Curse curse : chosen) {
            if (curse.id().equals(selected)) {
                return curse;
            }
        }
        return null;
    }

    /** Top-left corner of slot i, or null if it would not fit above the info area. */
    private static int[] slotPosition(int index) {
        int cols = Math.max(1, (boxW - 2 * PADDING + SLOT_GAP) / (SLOT + SLOT_GAP));
        int x = boxX + PADDING + (index % cols) * (SLOT + SLOT_GAP);
        int y = boxY + PADDING + (index / cols) * (SLOT + SLOT_GAP);
        if (y + SLOT > boxY + boxH - INFO_HEIGHT - 2) {
            return null;
        }
        return new int[] {x, y};
    }

    private static boolean isInSlot(double mouseX, double mouseY, int[] pos) {
        return mouseX >= pos[0] && mouseX < pos[0] + SLOT && mouseY >= pos[1] && mouseY < pos[1] + SLOT;
    }

    /**
     * The vanilla "Allow Cheats" button. It only exists while the Game tab is open, which is also how we
     * know when to show our own widgets.
     */
    private static CycleButton<?> findCheatsButton(Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof CycleButton<?> cycle && isCheatsLabel(cycle.getMessage())) {
                return cycle;
            }
        }
        return null;
    }

    /** The button's text is "<name>: <value>", i.e. a translatable whose first argument is the option name. */
    private static boolean isCheatsLabel(Component message) {
        if (message.getContents() instanceof TranslatableContents label) {
            for (Object arg : label.getArgs()) {
                if (arg instanceof Component name
                        && name.getContents() instanceof TranslatableContents key
                        && CHEATS_KEY.equals(key.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }
}
