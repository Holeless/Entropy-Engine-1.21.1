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
 * Vanilla-style scrolling list with one row per curse: symbol, name, description and a checkbox.
 * Click a row (or press Enter/Space on it) to toggle its curse.
 */
public class CurseList extends ObjectSelectionList<CurseList.Row> {
    private static final int ROW_HEIGHT = 40;
    private static final int ICON_SIZE = 32;

    // Vanilla checkbox sprites
    private static final ResourceLocation CHECKBOX = ResourceLocation.withDefaultNamespace("widget/checkbox");
    private static final ResourceLocation CHECKBOX_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_highlighted");
    private static final ResourceLocation CHECKBOX_SELECTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected");
    private static final ResourceLocation CHECKBOX_SELECTED_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected_highlighted");

    public CurseList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        for (Curse curse : Curses.all()) {
            this.addEntry(new Row(curse));
        }
    }

    @Override
    public int getRowWidth() {
        return Math.min(300, this.width - 40);
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

            // Symbol on a dark tile, outlined red when the curse is on
            graphics.fill(left + 3, top + 1, left + 3 + ICON_SIZE + 2, top + 1 + ICON_SIZE + 2, 0xFF000000);
            graphics.blit(curse.icon(), left + 4, top + 2, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, 16, 16, 16, 16);
            graphics.renderOutline(left + 3, top + 1, ICON_SIZE + 2, ICON_SIZE + 2, enabled ? 0xFFFF5555 : 0xFF555555);

            int textX = left + 4 + ICON_SIZE + 8;
            int textWidth = width - (textX - left) - 30;
            graphics.drawString(font, curse.name(), textX, top + 4, enabled ? 0xFF5555 : 0xFFFFFF, false);

            List<FormattedCharSequence> lines = font.split(curse.description(), textWidth);
            for (int i = 0; i < Math.min(lines.size(), 2); i++) {
                graphics.drawString(font, lines.get(i), textX, top + 16 + i * (font.lineHeight + 1), 0xA0A0A0, false);
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
