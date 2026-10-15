package com.example.curses.curse;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.curses.CursesMod;

import net.minecraft.resources.ResourceLocation;

/**
 * All curses known to the mod, in the order they are shown in the selection screen.
 *
 * <p>To add a curse: register it here, add its lang keys and a 16x16 symbol texture,
 * then implement its effect (see {@code FamineHandler} for an example).</p>
 */
public final class Curses {
    private static final Map<ResourceLocation, Curse> REGISTRY = new LinkedHashMap<>();

    /** Hunger depletes 50% faster. */
    public static final Curse FAMINE = register("famine");

    /** Creepers explode 50% faster. */
    public static final Curse QUICK_FUSE = register("quick_fuse");

    /** Rain and thunderstorms come much more often. */
    public static final Curse DELUGE = register("deluge");

    /** Undead, piglins and illagers may spawn with a Totem of Undying. */
    public static final Curse HYPOCRISY = register("hypocrisy");

    /** Spiders leave webs in their wake and try to catch players with them. */
    public static final Curse ENTANGLE = register("entangle");

    /** Phantoms pick up creepers and drop them on players. */
    public static final Curse PAYLOAD = register("payload");

    /** Players cannot sleep. */
    public static final Curse INSOMNIA = register("insomnia");

    /** Zombies, skeletons and piglins may spawn with a shield in their off hand and use it. */
    public static final Curse BASTION = register("bastion");

    /** Hostile mobs spawn twice as often. */
    public static final Curse INFEST = register("infest");

    /** Villages spawn with 3 Iron Golems; an angered golem hides from a player who is out of its reach. */
    public static final Curse FORTIFIED = register("fortified");

    /** Phantoms spawn every night regardless of sleep, and in caves too. */
    public static final Curse NOCTURNE = register("nocturne");

    /** Hostile mobs never get aggravated at each other. */
    public static final Curse UNITY = register("unity");

    /** Witches buff other mobs with splash potions, throw from further away and can throw lingering potions. */
    public static final Curse COVEN = register("coven");

    /** Witches can throw Molotov Cocktails; creepers cause fire when they explode. */
    public static final Curse INCENDIARY = register("incendiary");

    /** Skeletons sometimes shoot exploding arrows; witches may throw dynamite. */
    public static final Curse ORDNANCE = register("ordnance");

    /** Undead and piglins may spawn with an ender pearl in their off hand and use it to get to you. */
    public static final Curse WARPING = register("warping");

    /** Blazes and magma cubes spawn in lava pools in the overworld. */
    public static final Curse INFERNO = register("inferno");

    /** Armor and tools have 2/3 of their durability, so they wear out faster. */
    public static final Curse BRITTLE = register("brittle");

    /** Hostile mobs have 2.25x detection range. */
    public static final Curse SAGACIOUS = register("sagacious");

    /** Hostile mobs always know where you are, within render distance. */
    public static final Curse OMNISCIENCE = register("omniscience");

    /** Creepers may spawn 80% transparent, and those creepers make no sound when they ignite. */
    public static final Curse INCONSPICUOUS = register("inconspicuous");

    /** Ranged mobs back away from you while attacking, and hide behind cover between shots. */
    public static final Curse KITING = register("kiting");

    /** Pigs are Hoglins; cows and mooshrooms are Ravagers. */
    public static final Curse SELECTIVE_BREEDING = register("selective_breeding");

    /** Hostile mobs look for horses, donkeys and spiders to ride, which carry them to you. */
    public static final Curse BEAST_OF_BURDEN = register("beast_of_burden");

    /** Endermen move placed blocks and build pathways; undead and piglins (not skeletons) break blocks by hand. */
    public static final Curse BREACH = register("breach");

    private Curses() {
    }

    private static Curse register(String path) {
        Curse curse = new Curse(CursesMod.id(path));
        REGISTRY.put(curse.id(), curse);
        return curse;
    }

    public static Collection<Curse> all() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    /** Returns the curse with this id, or null if there is none. */
    public static Curse get(ResourceLocation id) {
        return REGISTRY.get(id);
    }

    /** Looks a curse up by the path of its id (e.g. "famine"), ignoring case. Returns null if there is none. */
    public static Curse byName(String name) {
        for (Curse curse : REGISTRY.values()) {
            if (curse.id().getPath().equalsIgnoreCase(name)) {
                return curse;
            }
        }
        return null;
    }
}
