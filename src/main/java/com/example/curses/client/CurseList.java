package com.example.curses.client;

import java.util.List;

import com.example.curses.curse.Curse;
import com.example.curses.curse.Curses;
import com.example.curses.curse.PendingCurses;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

/**
 * Vanilla-style scrolling list with one row per curse: symbol (a vanilla item), name, description and a checkbox.
 * Click a row (or press Enter/Space on it) to toggle its curse.
 *
 * <p>Rows are tall enough for the longest description at the current screen width, so every description is
 * shown in full (the list scrolls instead of cutting text off).</p>
 */
public class CurseList extends ObjectSelectionList<CurseList.Row> {
    private static final int ICON_SIZE = 32;
    private static final int TEXT_OFFSET = 4 + ICON_SIZE + 8; // icon tile plus gap, from the row's left edge
    private static final int RIGHT_RESERVE = 30;              // room for the checkbox
    private static final int MAX_ROW_WIDTH = 420;
    private static final int PAD = 4;

    // Vanilla checkbox sprites
    private static final ResourceLocation CHECKBOX = ResourceLocation.withDefaultNamespace("widget/checkbox");
    private static final ResourceLocation CHECKBOX_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_highlighted");
    private static final ResourceLocation CHECKBOX_SELECTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected");
    private static final ResourceLocation CHECKBOX_SELECTED_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected_highlighted");

    public CurseList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, rowHeight(minecraft, width));
        for (Curse curse : Curses.all()) {
            this.addEntry(new Row(curse));
        }
    }

    private static int rowWidth(int listWidth) {
        return Math.max(160, Math.min(MAX_ROW_WIDTH, listWidth - 40));
    }

    private static int textWidth(int rowWidth) {
        return Math.max(40, rowWidth - TEXT_OFFSET - RIGHT_RESERVE);
    }

    /** Height of the content of one row: padding, name lines, a small gap, description lines, padding. */
    private static int contentHeight(Font font, Curse curse, int textWidth) {
        int line = font.lineHeight + 1;
        return PAD + font.split(curse.name(), textWidth).size() * line + 2
                + font.split(curse.description(), textWidth).size() * line + PAD;
    }

    /** One height for all rows (the list needs a fixed one): the tallest curse decides. */
    private static int rowHeight(Minecraft minecraft, int listWidth) {
        Font font = minecraft.font;
        int textWidth = textWidth(rowWidth(listWidth));
        int content = ICON_SIZE + 4;
        for (Curse curse : Curses.all()) {
            content = Math.max(content, contentHeight(font, curse, textWidth));
        }
        return content + 4; // vanilla hands each row (item height - 4) to render()
    }

    @Override
    public int getRowWidth() {
        return rowWidth(this.width);
    }

    public class Row extends ObjectSelectionList.Entry<Row> {
        private final Curse curse;

        Row(Curse curse) {
            this.curse = curse;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", curse.name());
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            Font font = Minecraft.getInstance().font;
            boolean enabled = PendingCurses.isSelected(curse);

            // Row background: red tint when the curse is on, light tint under the mouse
            if (enabled) {
                graphics.fill(left, top - 2, left + width, top + height + 2, 0x30FF5555);
            }
            if (hovering) {
                graphics.fill(left, top - 2, left + width, top + height + 2, 0x20FFFFFF);
            }

            // Symbol on a dark tile, outlined red when the curse is on
            graphics.fill(left + 3, top + 1, left + 3 + ICON_SIZE + 2, top + 1 + ICON_SIZE + 2, 0xFF000000);
            ItemIcon.draw(graphics, curse.icon(), left + 4, top + 2, ICON_SIZE);
            graphics.renderOutline(left + 3, top + 1, ICON_SIZE + 2, ICON_SIZE + 2, enabled ? 0xFFFF5555 : 0xFF555555);

            int textX = left + TEXT_OFFSET;
            int textWidth = textWidth(width);
            int lineStep = font.lineHeight + 1;
            int y = top + PAD;

            List<FormattedCharSequence> nameLines = font.split(curse.name(), textWidth);
            for (FormattedCharSequence line : nameLines) {
                graphics.drawString(font, line, textX, y, enabled ? 0xFF5555 : 0xFFFFFF, true);
                y += lineStep;
            }
            y += 2;
            // The whole description: row height was sized for the longest one.
            for (FormattedCharSequence line : font.split(curse.description(), textWidth)) {
                graphics.drawString(font, line, textX, y, 0xC8C8C8, true);
                y += lineStep;
            }

            ResourceLocation sprite;
            if (enabled) {
                sprite = hovering ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED;
            } else {
                sprite = hovering ? CHECKBOX_HIGHLIGHTED : CHECKBOX;
            }
            graphics.blitSprite(sprite, left + width - 24, top + (height - 20) / 2, 20, 20);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                toggle();
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (CommonInputs.selected(keyCode)) {
                toggle();
                return true;
            }
            return false;
        }

        private void toggle() {
            PendingCurses.set(curse, !PendingCurses.isSelected(curse));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
