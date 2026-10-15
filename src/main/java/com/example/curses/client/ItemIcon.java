package com.example.curses.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Client only. Draws a vanilla item model at any size (items are 16x16 natively). */
public final class ItemIcon {
    private ItemIcon() {
    }

    public static void draw(GuiGraphics graphics, ItemStack stack, int x, int y, int size) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        float scale = size / 16.0F;
        pose.scale(scale, scale, 1.0F);
        graphics.renderFakeItem(stack, 0, 0);
        pose.popPose();
    }
}
