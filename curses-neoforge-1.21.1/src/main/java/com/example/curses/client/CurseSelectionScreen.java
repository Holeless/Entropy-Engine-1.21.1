package com.example.curses.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The "Curses" screen opened from the create-world screen. Returns to it when done. */
public class CurseSelectionScreen extends Screen {
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 36;

    private final Screen parent;

    public CurseSelectionScreen(Screen parent) {
        super(Component.translatable("curses.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(new CurseList(this.minecraft, this.width, this.height - HEADER_HEIGHT - FOOTER_HEIGHT, HEADER_HEIGHT));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
