package com.example.curses.client;

import java.util.ArrayList;
import java.util.List;

import com.example.curses.curse.Curse;
import com.example.curses.curse.Curses;
import com.example.curses.curse.PendingCurses;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The "Curses" screen opened from the create-world screen. Built like vanilla's Game Rules screen: a title on
 * top, a scrolling list of every curse (icon, name, full description, checkbox) and Clear All / Done at the bottom.
 * The title shows how many curses are chosen. Returns to the create-world screen when done (Esc works too).
 */
public class CurseSelectionScreen extends Screen {
    private static final int HEADER_HEIGHT = 33;
    private static final int FOOTER_HEIGHT = 36;

    private final Screen parent;
    private CurseList list;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);

    public CurseSelectionScreen(Screen parent) {
        super(Component.translatable("curses.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.list = this.addRenderableWidget(new CurseList(this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight()));

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable("curses.screen.clear"), button -> PendingCurses.clear())
                .width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(150).build());
        this.layout.visitWidgets(widget -> {
            if (!(widget instanceof CurseList)) {
                this.addRenderableWidget(widget);
            }
        });
        this.layout.arrangeElements();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("curses.screen.count", PendingCurses.count(), Curses.all().size()),
                this.width / 2, 20, PendingCurses.count() > 0 ? 0xFF5555 : 0xA0A0A0);

        // Full name and description of the row under the mouse (the rows only have room for one line)
        Curse hovered = this.list == null ? null : this.list.getHoveredCurse();
        if (hovered != null) {
            int maxWidth = Math.max(60, Math.min(260, this.width - 24));
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.addAll(this.font.split(hovered.name().copy().withStyle(ChatFormatting.RED), maxWidth));
            lines.addAll(this.font.split(hovered.description(), maxWidth));
            graphics.renderTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
