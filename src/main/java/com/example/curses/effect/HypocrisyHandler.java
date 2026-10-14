package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Hypocrisy: undead, piglins and illagers have a 5% chance to spawn with a Totem of Undying in their off hand.
 *
 * <p>Each mob rolls once (remembered in its saved data). Vanilla already makes a held totem work for any
 * mob: when a hit would kill it, the totem is used up, health is set to 1 and it gets Regeneration,
 * Absorption and Fire Resistance, with the usual totem particles and sound. So giving the totem is enough.
 * Piglins would throw it away, which {@code PiglinAiMixin} prevents.</p>
 *
 * <p>Skeletons are included like every other undead mob. Every successful roll is written to the debug log
 * ("Hypocrisy: gave a totem to ..."), so you can check in the console that skeletons do get them.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class HypocrisyHandler {
    /** Chance for each eligible mob to get a totem. */
    public static final float CHANCE = 0.05F;

    private static final String KEY_ROLLED = "curses_hypocrisy_rolled";

    private HypocrisyHandler() {
    }

    private static boolean isEligible(Mob mob) {
        return mob.getType().is(EntityTypeTags.UNDEAD) || mob instanceof AbstractIllager || mob instanceof AbstractPiglin;
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob) || !isEligible(mob)) {
            return;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(KEY_ROLLED) || !CurseData.isActive(level.getServer(), Curses.HYPOCRISY)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);
        if (mob.getRandom().nextFloat() >= CHANCE) {
            return;
        }

        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        mob.setDropChance(EquipmentSlot.OFFHAND, 0.0F); // the curse's totem is not loot
        CursesMod.LOGGER.debug("Hypocrisy: gave a totem to {} at {}", mob.getType(), mob.blockPosition());
    }
}
