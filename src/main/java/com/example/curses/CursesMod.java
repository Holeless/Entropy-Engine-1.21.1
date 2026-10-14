package com.example.curses;

import org.slf4j.Logger;

import com.example.curses.client.CursesClientSetup;
import com.example.curses.curse.Curses;
import com.example.curses.registry.CursesEntities;
import com.example.curses.registry.CursesItems;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Entry point of the Curses mod.
 *
 * <p>All behaviour lives in event-subscriber classes that register themselves via
 * {@code @EventBusSubscriber}, so this class only holds shared constants and registers the mod's items and
 * entities.</p>
 */
@Mod(CursesMod.MOD_ID)
public class CursesMod {
    // Must match mod_id in gradle.properties and the modId in neoforge.mods.toml
    public static final String MOD_ID = "curses";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CursesMod(IEventBus modEventBus) {
        CursesItems.ITEMS.register(modEventBus);
        CursesEntities.ENTITY_TYPES.register(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(CursesClientSetup::onRegisterRenderers);
        }
        LOGGER.info("Curses loaded with {} curse(s)", Curses.all().size());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
