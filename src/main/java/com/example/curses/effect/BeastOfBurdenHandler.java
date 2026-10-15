package com.example.curses.effect;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.CaveSpider;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Beast of Burden: hostile mobs look for a mount (horse, donkey, mule or spider) and ride it to you.
 *
 * <p><b>Riders:</b> zombies (husks, zombie villagers and zombified piglins too, not drowned), skeletons (strays,
 * wither skeletons, boggeds) and illagers. Every one of them gets a {@link SeekMountGoal} when it joins the world,
 * which does nothing while the curse is off. While a rider is hunting a player who is far away, it walks to the
 * nearest free mount and climbs on.</p>
 *
 * <p><b>Steering:</b> in vanilla a mob that rides a horse-type animal steers it with its own AI (the mount takes
 * the rider's navigation and move control), so a mounted zombie simply chases you at the horse's speed. Spiders
 * do not take orders, so a rider on a spider just tells the spider to go for the same player. Wild animals only:
 * tamed or saddled horses (a player's) are never taken. Server only.</p>
 *
 * <p>Works together with {@code Breach}: a mounted undead rider that is stuck at a wall breaks blocks from the
 * saddle (see {@code BlockBreakerHandler}).</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class BeastOfBurdenHandler {
    private static final int SPIDER_ORDER_INTERVAL = 20;

    /** Mounts a rider is already walking to, so two riders do not fight over one animal. Server thread only. */
    static final Set<Mob> CLAIMED = Collections.newSetFromMap(new WeakHashMap<>());

    private BeastOfBurdenHandler() {
    }

    static boolean enabled(Level level) {
        return level instanceof ServerLevel serverLevel && CurseData.isActive(serverLevel.getServer(), Curses.BEAST_OF_BURDEN);
    }

    static boolean isRider(Mob mob) {
        return (mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof AbstractIllager)
                && !(mob instanceof Drowned);
    }

    static boolean isMountType(Mob mob) {
        return mob instanceof Horse || mob instanceof Donkey || mob instanceof Mule
                || (mob instanceof Spider && !(mob instanceof CaveSpider));
    }

    private static boolean isFreeMount(Mob mob) {
        if (!isMountType(mob) || !mob.isAlive() || mob.isBaby() || mob.isVehicle() || mob.isPassenger()
                || mob.isLeashed() || CLAIMED.contains(mob)) {
            return false;
        }
        // Never steal a player's horse.
        return !(mob instanceof AbstractHorse horse) || (!horse.isTamed() && !horse.isSaddled());
    }

    /** The free mount nearest to the rider within {@code range} blocks, or null. */
    static Mob findMount(Mob rider, double range) {
        AABB box = rider.getBoundingBox().inflate(range, 6.0, range);
        List<Mob> found = rider.level().getEntitiesOfClass(Mob.class, box, BeastOfBurdenHandler::isFreeMount);
        return found.stream().min(Comparator.comparingDouble(rider::distanceToSqr)).orElse(null);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || !isRider(mob)) {
            return;
        }
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof SeekMountGoal) {
                return;
            }
        }
        // Priority 1 so it can interrupt the vanilla chase/attack goals (priority 2+) while it walks to the mount.
        mob.goalSelector.addGoal(1, new SeekMountGoal(mob));
    }

    /** A rider on a spider cannot steer it, so it makes the spider hunt the rider's target. */
    @SubscribeEvent
    public static void onRiderTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob rider) || rider.level().isClientSide() || !isRider(rider)) {
            return;
        }
        if (!(rider.getVehicle() instanceof Spider spider) || (rider.tickCount + rider.getId()) % SPIDER_ORDER_INTERVAL != 0) {
            return;
        }
        if (!enabled(rider.level())) {
            return;
        }
        if (rider.getTarget() instanceof Player player && player.isAlive() && !player.isCreative()
                && !player.isSpectator() && spider.getTarget() != player) {
            spider.setTarget(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CLAIMED.clear();
    }
}
