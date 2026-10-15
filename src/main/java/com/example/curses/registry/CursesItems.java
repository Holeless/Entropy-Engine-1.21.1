package com.example.curses.registry;

import com.example.curses.CursesMod;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Items added by the mod. None of them is obtainable: they only exist so that thrown or drunk things have a
 * texture.
 * <ul>
 *   <li>Molotov Cocktail: the item an angry villager throws under Incendiary (see {@code AngryVillagerHandler}).</li>
 *   <li>Dynamite: the item a witch throws at an Omniscience tunnel (see {@code BreachHandler}).</li>
 *   <li>Milk Potion: the item a witch throws under Coven to strip your effects (see {@code CovenWitchHandler}).</li>
 *   <li>Levitation Potion: the item a witch holds while she drinks to float up to you (see {@code CovenWitchHandler}).</li>
 * </ul>
 */
public final class CursesItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CursesMod.MOD_ID);

    public static final DeferredItem<Item> MOLOTOV = ITEMS.registerSimpleItem("molotov", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> DYNAMITE = ITEMS.registerSimpleItem("dynamite", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> MILK_POTION = ITEMS.registerSimpleItem("splash_potion_of_milk", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> LEVITATION_POTION = ITEMS.registerSimpleItem("potion_of_levitation", new Item.Properties().stacksTo(16));

    private CursesItems() {
    }
}
