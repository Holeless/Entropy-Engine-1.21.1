package com.example.curses.curse;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A world-wide curse. Name and description come from lang keys
 * {@code curse.<namespace>.<path>.name} / {@code .description}; the symbol is a vanilla item.
 */
public record Curse(ResourceLocation id, Item item) {

    public Component name() {
        return Component.translatable("curse." + id.getNamespace() + "." + id.getPath() + ".name");
    }

    public Component description() {
        return Component.translatable("curse." + id.getNamespace() + "." + id.getPath() + ".description");
    }

    /** The item that represents this curse in the UI. */
    public ItemStack icon() {
        return new ItemStack(item);
    }
}
