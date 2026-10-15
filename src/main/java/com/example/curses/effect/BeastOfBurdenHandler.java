package com.example.curses.effect;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.animal.horse.ZombieHorse;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.CaveSpider;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Beast of Burden: hostile mobs look for a mount (horse, donkey, mule, skeleton horse, zombie horse or spider) and
 * ride it to you.
 *
 * <p><b>Riders:</b> zombies (husks, zombie villagers and zombified piglins too, not drowned), skeletons (strays,
 * wither skeletons, boggeds) and illagers. Every one of them gets a {@link SeekMountGoal} when it joins the world,
 * which does nothing while the curse is off. While a rider is hunting a player who is far away, it walks to the
 * nearest free mount and climbs on.</p>
 *
 * <p><b>Born in the saddle:</b> every Skeleton has a {@link #HORSE_CHANCE} (1%) chance to appear already riding a
 * Skeleton Horse, and every Zombie (not husks' cousins the drowned, zombie villagers or zombified piglins) the same
 * chance to appear on a Zombie Horse. Each mob rolls once (remembered in its saved data).</p>
 *
 * <p><b>Who is in control:</b></p>
 * <ul>
 *   <li><b>Horse, donkey, mule, skeleton horse, zombie horse:</b> the rider is. In vanilla a mob that rides a
 *       horse-type animal steers it with its own AI (the mount takes the rider's navigation and move control), and
 *       {@code MobMixin} keeps the mount's own goals switched off, so it never wanders. The mount also runs flat out
 *       all the time while ridden: it is kept sprinting and gets a {@link #MOUNT_SPEED_BONUS} speed bonus (see
 *       {@link #onMountTick}).</li>
 *   <li><b>Spider:</b> the spider is. It keeps its own navigation and goals (see {@code MobMixin}) and hunts like a
 *       normal spider, climbing walls; the rider just points it at its own target (see {@link #onRiderTick}). If
 *       the rider is a ranged mob, the spider stops charging once the player is in firing range and holds its
 *       distance instead, backing away if the player gets close, while the rider shoots (see
 *       {@link JockeyKiteGoal}).</li>
 * </ul>
 *
 * <p>Wild animals only: tamed or saddled horses (a player's) are never taken. Server only.</p>
 *
 * <p>Works together with {@code Breach}: a mounted undead rider that is stuck at a wall breaks blocks from the
 * saddle (see {@code BlockBreakerHandler}).</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class BeastOfBurdenHandler {
    /** Chance for a Skeleton or Zombie to appear already riding a Skeleton Horse or Zombie Horse. */
    public static final float HORSE_CHANCE = 0.01F;

    private static final String KEY_HORSE_ROLLED = "curses_beast_horse_rolled";
    private static final int SPIDER_ORDER_INTERVAL = 10;
    private static final ResourceLocation MOUNT_SPEED_ID = CursesMod.id("beast_mount_speed");
    /** Extra speed of a ridden horse, donkey or mule, on top of the sprint boost (which is another +30%). */
    private static final double MOUNT_SPEED_BONUS = 0.25;

    /** Mounts a rider is already walking to, so two riders do not fight over one animal. Server thread only. */
    static final Set<Mob> CLAIMED = Collections.newSetFromMap(new WeakHashMap<>());

    private BeastOfBurdenHandler() {
    }

    public static boolean enabled(Level level) {
        return level instanceof ServerLevel serverLevel && CurseData.isActive(serverLevel.getServer(), Curses.BEAST_OF_BURDEN);
    }

    public static boolean isRider(Mob mob) {
        return (mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof AbstractIllager)
                && !(mob instanceof Drowned);
    }

    public static boolean isMountType(Mob mob) {
        return mob instanceof Horse || mob instanceof Donkey || mob instanceof Mule
                || mob instanceof SkeletonHorse || mob instanceof ZombieHorse
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
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (isRider(mob)) {
            for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
                if (wrapped.getGoal() instanceof SeekMountGoal) {
                    return;
                }
            }
            // Priority 1 so it can interrupt the vanilla chase/attack goals (priority 2+) while it walks to the mount.
            mob.goalSelector.addGoal(1, new SeekMountGoal(mob));
        } else if (mob instanceof Spider spider && !(mob instanceof CaveSpider)) {
            for (WrappedGoal wrapped : spider.goalSelector.getAvailableGoals()) {
                if (wrapped.getGoal() instanceof JockeyKiteGoal) {
                    return;
                }
            }
            // Priority 2 beats the spider's own attack goal (4) while the player is in the rider's firing range.
            spider.goalSelector.addGoal(2, new JockeyKiteGoal(spider));
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Born in the saddle
    // ---------------------------------------------------------------------------------------------------

    /** The horse a mob may spawn on, or null if this mob never does. */
    private static EntityType<? extends AbstractHorse> bornMount(Mob mob) {
        if (mob instanceof Skeleton) {
            return EntityType.SKELETON_HORSE;
        }
        if (mob instanceof Zombie && !(mob instanceof Drowned) && !(mob instanceof ZombieVillager)
                && !(mob instanceof ZombifiedPiglin)) {
            return EntityType.ZOMBIE_HORSE;
        }
        return null;
    }

    /**
     * Rolled from the mob's first tick rather than from the join event, because a new entity (the horse) must not
     * be added to the world from inside another entity's join.
     */
    @SubscribeEvent
    public static void onRiderRoll(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        EntityType<? extends AbstractHorse> type = bornMount(mob);
        if (type == null) {
            return;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(KEY_HORSE_ROLLED) || !mob.isAlive() || !enabled(mob.level())) {
            return;
        }
        data.putBoolean(KEY_HORSE_ROLLED, true);
        if (mob.isPassenger() || mob.isVehicle() || mob.getRandom().nextFloat() >= HORSE_CHANCE) {
            return;
        }

        ServerLevel level = (ServerLevel) mob.level();
        AbstractHorse horse = type.create(level);
        if (horse == null) {
            return;
        }
        horse.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), 0.0F);
        // EVENT rather than NATURAL so Infest does not double the horse.
        horse.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT, null);
        horse.setPersistenceRequired();
        level.addFreshEntity(horse);
        if (mob.startRiding(horse, true)) {
            CursesMod.LOGGER.debug("Beast of Burden: {} at {} spawned riding a {}", mob.getType(), mob.blockPosition(), horse.getType());
        } else {
            horse.discard();
        }
    }

    /** A horse-type mount with a rider runs flat out: sprinting plus a speed bonus, both dropped when it is free again. */
    @SubscribeEvent
    public static void onMountTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractHorse horse) || horse.level().isClientSide() || !isMountType(horse)) {
            return;
        }
        AttributeInstance speed = horse.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        boolean ridden = horse.getFirstPassenger() instanceof Mob rider && isRider(rider) && enabled(horse.level());
        boolean boosted = speed.hasModifier(MOUNT_SPEED_ID);
        if (ridden) {
            if (!boosted) {
                speed.addTransientModifier(new AttributeModifier(MOUNT_SPEED_ID, MOUNT_SPEED_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
            horse.setSprinting(true);
        } else if (boosted) {
            speed.removeModifier(MOUNT_SPEED_ID);
            horse.setSprinting(false);
        }
    }

    /** A rider on a spider points it at the rider's own target; from there the spider hunts on its own. */
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
