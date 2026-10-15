package com.example.curses.client;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Client only. Zombies that hold a pickaxe or an axe (Tools curse) are posed like a player: arms down at the sides,
 * swinging the tool the way a player swings it while mining. The zombie models call this around their pose code
 * (see the zombie model mixins).
 */
public final class ToolPose {
    private static LivingEntity current;

    private ToolPose() {
    }

    public static void begin(LivingEntity entity) {
        current = entity;
    }

    public static void end() {
        current = null;
    }

    /** True while the model being posed belongs to a zombie holding a digging tool. */
    public static boolean usePlayerPose() {
        if (current == null) {
            return false;
        }
        ItemStack held = current.getMainHandItem();
        return held.is(ItemTags.PICKAXES) || held.is(ItemTags.AXES);
    }
}
