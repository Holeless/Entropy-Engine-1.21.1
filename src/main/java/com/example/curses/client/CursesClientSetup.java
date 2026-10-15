package com.example.curses.client;

import com.example.curses.registry.CursesEntities;

import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only mod-bus setup. Only ever referenced when running on the physical client (see CursesMod). */
public final class CursesClientSetup {
    private CursesClientSetup() {
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CursesEntities.EXPLOSIVE_ARROW.get(), ExplosiveArrowRenderer::new);
        // Replaces the vanilla creeper renderer so that Inconspicuous creepers can be drawn see-through.
        event.registerEntityRenderer(EntityType.CREEPER, InconspicuousCreeperRenderer::new);
    }
}
