package com.example.curses.client;

import java.util.ArrayList;
import java.util.List;

import com.example.curses.curse.Curse;
import com.example.curses.curse.Curses;
import com.example.curses.curse.PendingCurses;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;

/**
 * The curses UI on the create-world screen (Game tab), kept as plain as a vanilla option:
 *
 * <ul>
 *   <li>a "Curses... (N)" button directly under "Allow Cheats", same width and height. Its tooltip lists the
 *       chosen curses by name.</li>
 *   <li>under the button, one small slot per chosen curse showing its item, like an inventory row. Hovering a
 *       slot shows that curse's name and description. If there is no room left above the Create / Cancel row the
 *       row ends with "+N" (the button's tooltip still lists everything).</li>
 * </ul>
 *
 * <p>Positioned relative to the vanilla widgets, so it follows vanilla's layout at any size. Client only.</p>
 */
public final class CursePanel {
    private static final String CHEATS_KEY = "selectWorld.allowCommands";
    private static final int ROW_GAP = 8;          // vanilla spacing between rows of the Game tab
    private static final int ICON_GAP = 4;         // space between the button and the icon row
    private static final int SLOT = 18;            // 16px item plus a pixel each side
    private static final int FOOTER_RESERVE = 38;  // never reach into the Create / Cancel row
    private static final int TOOLTIP_MAX_WIDTH = 220;

    private static Screen owner;
    private static Button button;
    private static String signature;

    private static boolean shown;
    private static int rowX;
    private static int rowY;
    private static int rowW;

    private CursePanel() {
    }

    public static void attach(Screen screen, Button curseButton) {
        owner = screen;
        button = curseButton;
        signature = null; // forces the label and tooltip to be written on the first frame
        shown = false;
    }

    public static void detach() {
        owner = null;
        button = null;
        signature = null;
        shown = false;
    }

    /** Places the button under the Game tab's buttons; hides everything on other tabs. */
    public static void layout(CreateWorldScreen screen) {
        shown = false;
        if (button == null || owner != screen) {
            return;
        }
        List<AbstractWidget> column = findGameColumn(screen);
        if (column.isEmpty()) {
            button.visible = false; // not the Game tab
            return;
        }

        AbstractWidget lowest = column.get(0);
        for (AbstractWidget widget : column) {
            if (widget.getY() > lowest.getY()) {
                lowest = widget;
            }
        }
        int buttonY = lowest.getY() + lowest.getHeight() + ROW_GAP;
        button.setX(lowest.getX());
        button.setY(buttonY);
        button.setWidth(lowest.getWidth());
        button.setHeight(lowest.getHeight());
        button.visible = true;

        refreshButton(chosenCurses());

        rowX = lowest.getX();
        rowY = buttonY + button.getHeight() + ICON_GAP;
        rowW = lowest.getWidth();
        shown = true;
    }

    /** Rewrites the label and tooltip, but only when the selection has changed. */
    private static void refreshButton(List<Curse> chosen) {
        StringBuilder sb = new StringBuilder();
        for (Curse curse : chosen) {
            sb.append(curse.id()).append(';');
        }
        String now = sb.toString();
        if (now.equals(signature)) {
            return;
        }
        signature = now;
        button.setMessage(Component.translatable("curses.create.button", chosen.size()));
        if (chosen.isEmpty()) {
            button.setTooltip(Tooltip.create(Component.translatable("curses.create.tooltip")));
            return;
        }
        MutableComponent text = Component.translatable("curses.create.tooltip.chosen").withStyle(ChatFormatting.GRAY);
        for (Curse curse : chosen) {
            text.append("\n").append(curse.name().copy().withStyle(ChatFormatting.RED));
        }
        button.setTooltip(Tooltip.create(text));
    }

    public static void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!shown || button == null || !button.visible) {
            return;
        }
        List<Curse> chosen = chosenCurses();
        if (chosen.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int perRow = Math.max(1, rowW / SLOT);
        int rowsFit = Math.max(0, (screenHeight - FOOTER_RESERVE - rowY) / SLOT);
        int capacity = perRow * rowsFit;
        boolean overflow = chosen.size() > capacity;
        int drawn = overflow ? Math.max(0, capacity - 1) : chosen.size();

        Curse hovered = null;
        for (int i = 0; i < drawn; i++) {
            int x = rowX + (i % perRow) * SLOT;
            int y = rowY + (i / perRow) * SLOT;
            Curse curse = chosen.get(i);
            boolean over = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            graphics.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0xFF000000);
            graphics.renderOutline(x, y, SLOT - 1, SLOT - 1, 0xFF555555);
            ItemIcon.draw(graphics, curse.icon(), x + 1, y + 1, 16);
            if (over) {
                hovered = curse;
                graphics.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, 0x80FFFFFF);
            }
        }
        if (overflow && capacity > 0) {
            int x = rowX + (drawn % perRow) * SLOT;
            int y = rowY + (drawn / perRow) * SLOT;
            graphics.drawString(font, "+" + (chosen.size() - drawn), x + 2, y + 5, 0xFFFFFF, true);
        }

        if (hovered != null) {
            int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int maxWidth = Math.max(60, Math.min(TOOLTIP_MAX_WIDTH, screenWidth - 24));
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.addAll(font.split(hovered.name().copy().withStyle(ChatFormatting.RED), maxWidth));
            lines.addAll(font.split(hovered.description(), maxWidth));
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
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

    /**
     * The widgets of the Game tab's column of buttons (empty when another tab is open). The Game tab is
     * recognised by its world-name box, with the "Allow Commands" button as a fallback.
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
