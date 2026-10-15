package com.example.curses.client;

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

/**
 * Compact vanilla-style scrolling list, one slim row per curse: item, name, a one-line description and a
 * checkbox. Names are lined up in a column and the description fills the rest of the row, cut with "..." if it
 * does not fit; hovering a row shows the full text (see {@link CurseSelectionScreen}). Click a row (or press
 * Enter/Space on it) to toggle its curse.
 */
public class CurseList extends ObjectSelectionList<CurseList.Row> {
    private static final int ITEM_HEIGHT = 22;        // row pitch; vanilla hands (this - 4) to render()
    private static final int TILE = 18;               // 16px item with a 1px border
    private static final int TEXT_OFFSET = 3 + TILE + 6;
    private static final int RIGHT_RESERVE = 26;      // room for the checkbox
    private static final int NAME_GAP = 10;
    private static final int MAX_ROW_WIDTH = 560;
    private static final int CHECKBOX = 17;

    private static final ResourceLocation SPRITE_OFF = ResourceLocation.withDefaultNamespace("widget/checkbox");
    private static final ResourceLocation SPRITE_OFF_HOVER = ResourceLocation.withDefaultNamespace("widget/checkbox_highlighted");
    private static final ResourceLocation SPRITE_ON = ResourceLocation.withDefaultNamespace("widget/checkbox_selected");
    private static final ResourceLocation SPRITE_ON_HOVER = ResourceLocation.withDefaultNamespace("widget/checkbox_selected_highlighted");

    private final int nameWidth;
    /** The curse under the mouse this frame, for the screen's tooltip. */
    private Curse hoveredCurse;

    public CurseList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, ITEM_HEIGHT);
        int widest = 0;
        for (Curse curse : Curses.all()) {
            widest = Math.max(widest, minecraft.font.width(curse.name()));
            this.addEntry(new Row(curse));
        }
        this.nameWidth = widest;
    }

    public Curse getHoveredCurse() {
        return hoveredCurse;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredCurse = null;
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public int getRowWidth() {
        return Math.min(MAX_ROW_WIDTH, this.width - 40);
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
            if (hovering) {
                hoveredCurse = curse;
            }

            // Row background: red tint when on, light tint under the mouse
            if (enabled) {
                graphics.fill(left, top - 2, left + width, top + height + 2, 0x30FF5555);
            }
            if (hovering) {
                graphics.fill(left, top - 2, left + width, top + height + 2, 0x20FFFFFF);
            }

            // Item, centred in its tile, tile centred in the row
            int tileX = left + 3;
            int tileY = top + (height - TILE) / 2;
            graphics.fill(tileX, tileY, tileX + TILE, tileY + TILE, 0xFF000000);
            graphics.renderOutline(tileX, tileY, TILE, TILE, enabled ? 0xFFFF5555 : 0xFF555555);
            ItemIcon.draw(graphics, curse.icon(), tileX + 1, tileY + 1, 16);

            // Name column, then the one-line description
            int textY = top + (height - font.lineHeight) / 2 + 1;
            int nameX = left + TEXT_OFFSET;
            graphics.drawString(font, curse.name(), nameX, textY, enabled ? 0xFF5555 : 0xFFFFFF, true);

            int descX = nameX + nameWidth + NAME_GAP;
            int available = left + width - RIGHT_RESERVE - descX;
            if (available > 20) {
                String text = curse.description().getString();
                if (font.width(text) > available) {
                    text = font.plainSubstrByWidth(text, available - font.width("...")) + "...";
                }
                graphics.drawString(font, text, descX, textY, 0xC8C8C8, true);
            }

            ResourceLocation sprite = enabled
                    ? (hovering ? SPRITE_ON_HOVER : SPRITE_ON)
                    : (hovering ? SPRITE_OFF_HOVER : SPRITE_OFF);
            graphics.blitSprite(sprite, left + width - CHECKBOX - 6, top + (height - CHECKBOX) / 2, CHECKBOX, CHECKBOX);
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
