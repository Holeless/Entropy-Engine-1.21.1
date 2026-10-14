package com.example.curses.registry;

import com.example.curses.CursesMod;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Items added by the mod. The Molotov Cocktail is not obtainable: it only exists as the item a witch throws
 * (see {@code IncendiaryHandler}), so the thrown projectile shows the molotov texture.
 */
public final class CursesItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CursesMod.MOD_ID);

    public static final DeferredItem<Item> MOLOTOV = ITEMS.registerSimpleItem("molotov", new Item.Properties().stacksTo(16));

    private CursesItems() {
    }
}
