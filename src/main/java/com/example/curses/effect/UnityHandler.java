package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * Unity: hostile mobs never get aggravated at each other.
 *
 * <p>Any attempt by a hostile mob to target another hostile mob (retaliation, infighting, hunting) is
 * turned into "keep whatever you were already targeting". Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class UnityHandler {
    private UnityHandler() {
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Enemy) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(event.getNewAboutToBeSetTarget() instanceof Enemy)) {
            return;
        }
        if (mob.level().isClientSide() || mob.getServer() == null || !CurseData.isActive(mob.getServer(), Curses.UNITY)) {
            return;
        }

        event.setNewAboutToBeSetTarget(mob.getTarget());
    }
}
