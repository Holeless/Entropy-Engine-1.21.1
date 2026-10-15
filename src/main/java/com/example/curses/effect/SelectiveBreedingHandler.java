package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Selective Breeding: pigs are Hoglins; cows and mooshrooms are Ravagers.
 *
 * <p><b>Conversion.</b> A pig, cow or mooshroom that ticks while the curse is on is converted in place (same spot,
 * same name, still a baby if it was one) into a Hoglin or a Ravager. It is driven from the entity tick rather than
 * from the join event, so it catches animals however they arrived (world generation, chunk loading, spawn eggs,
 * breeding, or an animal that was already there when the curse was added). Ravagers have no baby form, so a calf
 * becomes a full Ravager.</p>
 *
 * <p>Hoglins are made immune to zombification: in the overworld they would otherwise turn into Zoglins after 15
 * seconds. The new mobs are persistent, like farm animals, so they never despawn.</p>
 *
 * <p><b>No refills.</b> Vanilla keeps topping animals up with periodic natural spawns. Those would now arrive as
 * an endless supply of persistent Ravagers, so while the curse is on, natural (periodic) pig and cow spawns are
 * cancelled. Animals made by world generation are not natural spawns and are converted as normal.</p>
 *
 * <p>Left alone: saddled pigs and anything being ridden, and everything on Peaceful (a hostile Hoglin or
 * Ravager would just despawn there). Conversion is one way: lifting the curse does not turn them back. Server
 * only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class SelectiveBreedingHandler {
    private static final int CHECK_INTERVAL = 10; // ticks between checks per animal

    private SelectiveBreedingHandler() {
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Pig) && !(entity instanceof Cow)) {
            return;
        }
        if (entity.level().isClientSide() || (entity.tickCount + entity.getId()) % CHECK_INTERVAL != 0) {
            return;
        }
        ServerLevel level = (ServerLevel) entity.level();
        Mob animal = (Mob) entity;
        if (!animal.isAlive() || animal.isVehicle() || animal.isPassenger()
                || (animal instanceof Pig pig && pig.isSaddled())) {
            return;
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL || !CurseData.isActive(level.getServer(), Curses.SELECTIVE_BREEDING)) {
            return;
        }

        double x = animal.getX();
        double y = animal.getY();
        double z = animal.getZ();
        if (animal instanceof Pig) {
            Hoglin hoglin = animal.convertTo(EntityType.HOGLIN, true);
            if (hoglin == null) {
                return;
            }
            hoglin.setImmuneToZombification(true);
            hoglin.setPersistenceRequired();
        } else {
            Ravager ravager = animal.convertTo(EntityType.RAVAGER, true);
            if (ravager == null) {
                return;
            }
            ravager.setPersistenceRequired();
        }
        level.sendParticles(ParticleTypes.POOF, x, y + 0.5, z, 12, 0.3, 0.4, 0.3, 0.03);
        CursesMod.LOGGER.debug("Selective Breeding: converted a {} at {}", entity.getType(), entity.blockPosition());
    }

    /** Periodic natural pig and cow spawns would only feed an endless supply of Hoglins and Ravagers. */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL) {
            return;
        }
        Mob mob = event.getEntity();
        if (!(mob instanceof Pig) && !(mob instanceof Cow)) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL || !CurseData.isActive(level.getServer(), Curses.SELECTIVE_BREEDING)) {
            return;
        }
        event.setSpawnCancelled(true);
    }
}
