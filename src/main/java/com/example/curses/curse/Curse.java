package com.example.curses.curse;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A world-wide curse. A curse is identified by its id and everything visual is derived from it:
 *
 * <ul>
 *   <li>name: lang key {@code curse.<namespace>.<path>.name}</li>
 *   <li>description: lang key {@code curse.<namespace>.<path>.description}</li>
 *   <li>symbol: texture {@code assets/<namespace>/textures/gui/curse/<path>.png} (16x16)</li>
 * </ul>
 */
public record Curse(ResourceLocation id) {

    public Component name() {
        return Component.translatable("curse." + id.getNamespace() + "." + id.getPath() + ".name");
    }

    public Component description() {
        return Component.translatable("curse." + id.getNamespace() + "." + id.getPath() + ".description");
    }

    public ResourceLocation icon() {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/gui/curse/" + id.getPath() + ".png");
    }
}
