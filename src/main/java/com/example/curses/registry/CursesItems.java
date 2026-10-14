package com.example.curses.registry;

import com.example.curses.CursesMod;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Items added by the mod. None of them is obtainable: they only exist so that thrown or shot things have a
 * texture.
 * <ul>
 *   <li>Molotov Cocktail: the item a witch throws under Incendiary (see {@code IncendiaryHandler}).</li>
 *   <li>Dynamite: the item a witch throws under Ordnance (see {@code OrdnanceHandler}).</li>
 *   <li>Explosive Arrow: the sprite the Ordnance arrow entity is drawn with.</li>
 * </ul>
 */
public final class CursesItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CursesMod.MOD_ID);

    public static final DeferredItem<Item> MOLOTOV = ITEMS.registerSimpleItem("molotov", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> DYNAMITE = ITEMS.registerSimpleItem("dynamite", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> EXPLOSIVE_ARROW = ITEMS.registerSimpleItem("explosive_arrow", new Item.Properties());

    private CursesItems() {
    }
}
