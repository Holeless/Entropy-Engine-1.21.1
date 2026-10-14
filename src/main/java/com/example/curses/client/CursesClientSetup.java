package com.example.curses.client;

import com.example.curses.registry.CursesEntities;

import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only mod-bus setup. Only ever referenced when running on the physical client (see CursesMod). */
public final class CursesClientSetup {
    private CursesClientSetup() {
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CursesEntities.EXPLOSIVE_ARROW.get(), ExplosiveArrowRenderer::new);
    }
}
