package com.example.curses.client;

import java.util.ArrayList;
import java.util.List;

import com.example.curses.CursesMod;
import com.example.curses.curse.Curse;
import com.example.curses.curse.Curses;
import com.example.curses.curse.PendingCurses;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
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
    private static Screen loggedScreen;

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

    /** Places the button and the box under the Game tab's buttons; hides both on other tabs. */
    public static void layout(CreateWorldScreen screen) {
        shown = false;
        if (button == null || owner != screen) {
            return;
        }
        List<AbstractWidget> column = findGameColumn(screen);
        if (column.isEmpty()) {
            button.visible = false; // not the Game tab
            logOnce(screen);
            return;
        }

        // The column is [world name box, game mode, difficulty, allow commands]: the Curses button goes
        // under the lowest one, the box spans the whole column down to the Curses button.
        AbstractWidget lowest = column.get(0);
        int top = Integer.MAX_VALUE;
        for (AbstractWidget widget : column) {
            if (widget.getY() > lowest.getY()) {
                lowest = widget;
            }
            top = Math.min(top, widget.getY());
        }
        int colX = lowest.getX();
        int colW = lowest.getWidth();
        int buttonY = lowest.getY() + lowest.getHeight() + ROW_GAP;

        button.setX(colX);
        button.setY(buttonY);
        button.setWidth(colW);
        button.setHeight(lowest.getHeight());
        button.visible = true;

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
     * The widgets of the Game tab's column of buttons (empty when another tab is open). The Game tab is
     * recognised by its world-name box (the only text box whose content is the world's name), with the
     * "Allow Commands" button as a fallback.
     */
    private static List<AbstractWidget> findGameColumn(CreateWorldScreen screen) {
        AbstractWidget reference = null;

        String worldName = screen.getUiState().getName();
        if (!worldName.isEmpty()) {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof EditBox box && worldName.equals(box.getValue())) {
                    reference = box;
                    break;
                }
            }
        }
        if (reference == null) {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof CycleButton<?> cycle && isCheatsLabel(cycle.getMessage())) {
                    reference = cycle;
                    break;
                }
            }
        }

        List<AbstractWidget> column = new ArrayList<>();
        if (reference == null) {
            return column;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != button && widget.visible
                    && Math.abs(widget.getX() - reference.getX()) <= 4) {
                column.add(widget);
            }
        }
        return column;
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

    /** Diagnostics: if the Game tab is never found, list what the screen contains (once per screen). */
    private static void logOnce(CreateWorldScreen screen) {
        if (loggedScreen == screen) {
            return;
        }
        // Only log when no tab content at all is recognised on the very first frames; other tabs are normal
        loggedScreen = screen;
        StringBuilder sb = new StringBuilder("Create-world widgets (Game tab not recognised yet):");
        for (GuiEventListener child : screen.children()) {
            sb.append("\n  ").append(child.getClass().getSimpleName());
            if (child instanceof AbstractWidget widget) {
                sb.append(" '").append(widget.getMessage().getString()).append("' x=").append(widget.getX())
                        .append(" y=").append(widget.getY()).append(" w=").append(widget.getWidth());
            }
        }
        CursesMod.LOGGER.info(sb.toString());
    }
}
