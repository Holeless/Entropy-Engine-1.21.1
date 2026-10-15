package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Sagacious: hostile mobs have 2.25 times the detection range.
 *
 * <p>Vanilla mobs notice players (and path toward them) out to their {@code follow_range} attribute: 16 blocks
 * for most, 35 for zombies, and so on. While the curse is active every hostile mob gets a +125% modifier on that
 * attribute, so everything that uses it (spotting a target, chasing it, pathfinding to it) reaches 2.25 times as far.</p>
 *
 * <p>The modifier is applied when a mob joins the world and re-checked every 5 seconds, so mobs that already
 * exist pick it up when the curse is added and lose it again when it is lifted. It is not saved with the mob.
 * Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class SagaciousHandler {
    private static final ResourceLocation MODIFIER_ID = CursesMod.id("sagacious_follow_range");
    /** Bonus on the base follow range: base x (1 + 1.25) = 2.25x. */
    private static final double BONUS = 1.25;
    private static final int CHECK_INTERVAL = 100; // ticks

    private SagaciousHandler() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) {
            return;
        }
        apply(mob, CurseData.isActive(level.getServer(), Curses.SAGACIOUS));
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy) || mob.level().isClientSide()) {
            return;
        }
        if ((mob.tickCount + mob.getId()) % CHECK_INTERVAL != 0) {
            return;
        }
        apply(mob, CurseData.isActive(((ServerLevel) mob.level()).getServer(), Curses.SAGACIOUS));
    }

    private static void apply(Mob mob, boolean on) {
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null) {
            return;
        }
        boolean has = range.hasModifier(MODIFIER_ID);
        if (on && !has) {
            // Base x (1 + 1.25) = 2.25x
            range.addTransientModifier(new AttributeModifier(MODIFIER_ID, BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        } else if (!on && has) {
            range.removeModifier(MODIFIER_ID);
        }
    }
}
