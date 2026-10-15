package com.example.curses.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.registry.CursesItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Coven, the witches' tricks (the spawning itself is in {@link CovenHandler}). All of it is server only and does
 * nothing while the Coven curse is off.
 *
 * <ul>
 *   <li><b>Self-levitation.</b> A witch that is hunting a player who is well above her (a tower, a pillar, a
 *       ledge) and has no walking route to them drinks a Levitation potion. She holds the mod's own
 *       {@code curses:potion_of_levitation} item (your texture) for the normal 32-tick drink; vanilla's witch code only
 *       applies the effects of a real potion, so when the drink ends this handler applies Levitation itself. While
 *       levitating she is nudged sideways toward the player. When she has risen to the player's height the
 *       levitation is cut and she gets a short Slow Falling, so she drifts down next to them instead of rising into
 *       the sky or taking fall damage.</li>
 *   <li><b>Curse cleansing.</b> A witch with a player in sight 5-14 blocks away lobs a Milk Potion when the
 *       player has more positive effects than negative ones. The potion is the mod's own
 *       {@code curses:splash_potion_of_milk} item (your texture), tagged so that on impact it clears every effect from
 *       players in the splash radius (what milk does). Nothing else is touched.</li>
 *   <li><b>Ally support.</b> A witch that sees a burning hostile mob nearby throws a vanilla Splash Potion of Water
 *       at it (this puts the fire out). A moment later, if that mob is low on health, she most likely follows with
 *       a Splash Potion of Regeneration. Neither is ever thrown if a player is anywhere near the splash, and
 *       mobs that water would hurt (Endermen, Blazes...) are never targeted.</li>
 * </ul>
 *
 * <p>The witch's normal potion throwing (poison, harming, the occasional buff for other mobs) is untouched; these
 * are extra actions that are checked twice a second per witch, with cooldowns.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class CovenWitchHandler {
    private static final String KEY_MILK = "curses_coven_milk";

    private static final int CHECK_INTERVAL = 10;          // ticks between decisions per witch

    // Levitation
    private static final int LEV_AMPLIFIER = 3;            // Levitation IV: about 0.2 blocks per tick
    private static final double LEV_MIN_RISE = 3.0;        // the player must be at least this far above
    private static final double LEV_MAX_RISE = 40.0;
    private static final double LEV_RANGE_SQR = 22.0 * 22.0; // sideways + vertical distance limit
    private static final float LEV_CHANCE = 0.5F;          // per check
    private static final int LEV_COOLDOWN = 400;
    private static final int LEV_RECHECK = 100;            // after finding the player walkable, look again later
    private static final int MAX_FLIGHT = 300;             // ticks after which a flight is dropped
    private static final int FLIGHT_START_GRACE = 80;      // drink never finished: forget it after this
    private static final double FLIGHT_SPEED = 0.14;       // sideways blocks per tick while levitating
    private static final double ARRIVE_DIST = 1.5;

    // Milk
    private static final double MILK_MIN = 5.0;
    private static final double MILK_MAX = 14.0;
    private static final float MILK_CHANCE = 0.4F;         // per check
    private static final int MILK_COOLDOWN = 300;          // per witch
    private static final int MILK_PLAYER_GRACE = 160;      // per player: no second milk this soon, whichever witch
    private static final int MILK_COLOR = 0xFFFFFF;        // splash particle colour on impact

    // Ally support
    private static final double ALLY_RANGE = 14.0;
    private static final double ALLY_MIN = 3.0;
    private static final float ALLY_CHANCE = 0.6F;         // per check, while a burning ally is in sight
    private static final int ALLY_COOLDOWN = 100;
    private static final int FOLLOW_DELAY = 12;            // ticks between the water and the regeneration check
    private static final float REGEN_CHANCE = 0.8F;        // "most likely"
    private static final float LOW_HEALTH = 0.5F;          // regeneration is for allies at or below this share of health
    private static final double SPLASH_RADIUS = 4.0;       // vanilla splash radius
    private static final double PLAYER_CLEARANCE = SPLASH_RADIUS + 0.5;

    private static final class Flight {
        final Player player;
        final long started;
        final int duration;      // ticks of Levitation to apply when the drink finishes
        boolean applied;         // Levitation has been applied
        boolean airborne;

        Flight(Player player, long started, int duration) {
            this.player = player;
            this.started = started;
            this.duration = duration;
        }
    }

    private static final class Follow {
        final Mob ally;
        final long due;

        Follow(Mob ally, long due) {
            this.ally = ally;
            this.due = due;
        }
    }

    // Server thread only.
    private static final Map<Witch, Flight> FLIGHTS = new WeakHashMap<>();
    private static final Map<Witch, Follow> FOLLOWS = new WeakHashMap<>();
    private static final Map<Witch, Long> LEV_READY = new WeakHashMap<>();
    private static final Map<Witch, Long> MILK_READY = new WeakHashMap<>();
    private static final Map<Witch, Long> ALLY_READY = new WeakHashMap<>();
    private static final Map<Player, Long> MILKED = new WeakHashMap<>();
    /** True while a milk potion is being thrown, so the join event can tag it. */
    private static boolean throwingMilk;

    private CovenWitchHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.COVEN);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FLIGHTS.clear();
        FOLLOWS.clear();
        LEV_READY.clear();
        MILK_READY.clear();
        ALLY_READY.clear();
        MILKED.clear();
        throwingMilk = false;
    }

    private static boolean valid(Witch witch, Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative() && player.level() == witch.level();
    }

    // ---------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onWitchTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Witch witch) || witch.level().isClientSide() || !witch.isAlive()) {
            return;
        }
        ServerLevel level = (ServerLevel) witch.level();
        long now = level.getGameTime();

        Flight flight = FLIGHTS.get(witch);
        if (flight != null) {
            tickFlight(witch, flight, now);
        }
        Follow follow = FOLLOWS.get(witch);
        if (follow != null && now >= follow.due) {
            FOLLOWS.remove(witch);
            tryRegeneration(witch, level, follow.ally);
        }

        if ((witch.tickCount + witch.getId()) % CHECK_INTERVAL != 0) {
            return;
        }
        if (witch.isDrinkingPotion() || witch.isUsingItem() || witch.isPassenger() || !enabled(level)) {
            return;
        }
        if (tryAllySupport(witch, level, now) || tryMilk(witch, level, now)) {
            return;
        }
        tryLevitation(witch, level, now);
    }

    // ---------------------------------------------------------------------------------------------------
    // Self-levitation
    // ---------------------------------------------------------------------------------------------------

    private static void tryLevitation(Witch witch, ServerLevel level, long now) {
        if (FLIGHTS.containsKey(witch) || !witch.onGround() || !witch.getMainHandItem().isEmpty()) {
            return;
        }
        Long ready = LEV_READY.get(witch);
        if (ready != null && now < ready) {
            return;
        }
        Player player = witch.getTarget() instanceof Player target ? target : level.getNearestPlayer(witch, 24.0);
        if (player == null || !valid(witch, player)) {
            return;
        }
        double rise = player.getY() - witch.getY();
        if (rise < LEV_MIN_RISE || rise > LEV_MAX_RISE || witch.distanceToSqr(player) > LEV_RANGE_SQR) {
            return;
        }
        if (witch.getRandom().nextFloat() >= LEV_CHANCE) {
            return;
        }
        // The path search is the costly part, so it only happens after the roll.
        if (Siege.canWalkTo(witch, player)) {
            LEV_READY.put(witch, now + LEV_RECHECK);
            return;
        }
        LEV_READY.put(witch, now + LEV_COOLDOWN);

        double perTick = 0.05 * (LEV_AMPLIFIER + 1);
        int duration = Math.min(200, (int) Math.ceil(rise / perTick) + 20);

        // The witch holds the mod's own levitation potion item (your texture) for the normal drinking time. Vanilla
        // clears her hand when the drink ends but only applies effects for a real potion, so tickFlight applies
        // the Levitation itself at that moment.
        ItemStack potion = new ItemStack(CursesItems.LEVITATION_POTION.get());
        witch.setItemSlot(EquipmentSlot.MAINHAND, potion);
        witch.setUsingItem(true);
        witch.usingTime = potion.getUseDuration(witch);
        witch.getNavigation().stop();
        level.playSound(null, witch.getX(), witch.getY(), witch.getZ(), SoundEvents.WITCH_DRINK, witch.getSoundSource(),
                1.0F, 0.8F + witch.getRandom().nextFloat() * 0.4F);

        FLIGHTS.put(witch, new Flight(player, now, duration));
        CursesMod.LOGGER.debug("Coven: witch at {} drinks levitation to reach a player {} blocks above", witch.blockPosition(), (int) rise);
    }

    private static void tickFlight(Witch witch, Flight flight, long now) {
        Player player = flight.player;
        if (!valid(witch, player) || now - flight.started > MAX_FLIGHT) {
            endFlight(witch);
            return;
        }
        boolean levitating = witch.hasEffect(MobEffects.LEVITATION);

        // The drink has finished (vanilla emptied her hand): apply the levitation the potion stands for.
        if (!levitating && !flight.applied && !flight.airborne && !witch.isDrinkingPotion() && now > flight.started + 1) {
            if (witch.getMainHandItem().is(CursesItems.LEVITATION_POTION.get())) {
                witch.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            witch.addEffect(new MobEffectInstance(MobEffects.LEVITATION, flight.duration, LEV_AMPLIFIER));
            flight.applied = true;
            levitating = true;
        }

        if (levitating) {
            flight.airborne = true;
        } else if (flight.airborne) {
            if (witch.onGround() || witch.isInWater()) {
                FLIGHTS.remove(witch); // landed
                return;
            }
        } else {
            if (now - flight.started > FLIGHT_START_GRACE) {
                if (witch.getMainHandItem().is(CursesItems.LEVITATION_POTION.get())) {
                    witch.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
                FLIGHTS.remove(witch); // the drink never finished
            }
            return; // still drinking
        }

        witch.resetFallDistance();
        witch.getLookControl().setLookAt(player, 30.0F, 30.0F);

        double dx = player.getX() - witch.getX();
        double dz = player.getZ() - witch.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > ARRIVE_DIST) {
            Vec3 motion = witch.getDeltaMovement();
            double wantX = dx / dist * FLIGHT_SPEED;
            double wantZ = dz / dist * FLIGHT_SPEED;
            witch.setDeltaMovement(motion.x + (wantX - motion.x) * 0.25, motion.y, motion.z + (wantZ - motion.z) * 0.25);
        }

        // At the player's height: stop rising and come down gently.
        if (levitating && witch.getY() >= player.getY() - 0.3) {
            settle(witch);
        }
    }

    private static void endFlight(Witch witch) {
        FLIGHTS.remove(witch);
        if (witch.hasEffect(MobEffects.LEVITATION)) {
            settle(witch);
        }
    }

    private static void settle(Witch witch) {
        witch.removeEffect(MobEffects.LEVITATION);
        witch.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0));
    }

    // ---------------------------------------------------------------------------------------------------
    // Curse cleansing (milk)
    // ---------------------------------------------------------------------------------------------------

    private static boolean tryMilk(Witch witch, ServerLevel level, long now) {
        Long ready = MILK_READY.get(witch);
        if (ready != null && now < ready) {
            return false;
        }
        if (!(witch.getTarget() instanceof Player player) || !valid(witch, player) || !moreGoodThanBad(player)) {
            return false;
        }
        double distance = witch.distanceTo(player);
        if (distance < MILK_MIN || distance > MILK_MAX || !witch.hasLineOfSight(player)) {
            return false;
        }
        Long last = MILKED.get(player);
        if (last != null && now - last < MILK_PLAYER_GRACE) {
            return false;
        }
        if (witch.getRandom().nextFloat() >= MILK_CHANCE) {
            return false;
        }

        // The mod's own milk potion item (your texture). It is not a vanilla potion, so nothing else reacts to it;
        // onImpact below clears the effects.
        ItemStack milk = new ItemStack(CursesItems.MILK_POTION.get());
        if (!lob(witch, level, player.position().add(0.0, 1.0, 0.0), milk, true)) {
            MILK_READY.put(witch, now + 40);
            return false;
        }
        witch.swing(InteractionHand.MAIN_HAND);
        MILK_READY.put(witch, now + MILK_COOLDOWN);
        MILKED.put(player, now);
        return true;
    }

    /** More positive effects than negative ones (and at least one). Neutral effects do not count. */
    private static boolean moreGoodThanBad(Player player) {
        int good = 0;
        int bad = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            switch (effect.getEffect().value().getCategory()) {
                case BENEFICIAL -> good++;
                case HARMFUL -> bad++;
                default -> {
                }
            }
        }
        return good > 0 && good > bad;
    }

    /** Tags the milk potion as it enters the world. */
    @SubscribeEvent
    public static void onPotionJoin(EntityJoinLevelEvent event) {
        if (throwingMilk && event.getEntity() instanceof ThrownPotion potion) {
            potion.getPersistentData().putBoolean(KEY_MILK, true);
        }
    }

    /** A milk splash clears every effect from the players inside its radius, like drinking milk. */
    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.level() instanceof ServerLevel level)
                || !potion.getPersistentData().getBoolean(KEY_MILK)) {
            return;
        }
        event.setCanceled(true);
        if (potion.isRemoved()) {
            return;
        }
        Vec3 at = event.getRayTraceResult().getLocation();
        for (Player player : level.getEntitiesOfClass(Player.class, AABB.ofSize(at, 8.0, 4.0, 8.0))) {
            if (!player.isSpectator() && player.distanceToSqr(at) < SPLASH_RADIUS * SPLASH_RADIUS) {
                player.removeAllEffects();
            }
        }
        level.levelEvent(2002, BlockPos.containing(at), MILK_COLOR); // glass shatter + splash particles
        potion.discard();
    }

    // ---------------------------------------------------------------------------------------------------
    // Ally support (water, then regeneration)
    // ---------------------------------------------------------------------------------------------------

    private static boolean tryAllySupport(Witch witch, ServerLevel level, long now) {
        Long ready = ALLY_READY.get(witch);
        if (ready != null && now < ready) {
            return false;
        }
        Mob ally = findBurningAlly(witch);
        if (ally == null || witch.getRandom().nextFloat() >= ALLY_CHANCE) {
            return false;
        }
        Vec3 aim = ally.position().add(0.0, 0.5, 0.0);
        if (playerNear(level, aim)) {
            return false;
        }
        ItemStack water = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER);
        if (!lob(witch, level, aim, water, false)) {
            ALLY_READY.put(witch, now + 40);
            return false;
        }
        witch.swing(InteractionHand.MAIN_HAND);
        ALLY_READY.put(witch, now + ALLY_COOLDOWN);
        FOLLOWS.put(witch, new Follow(ally, now + FOLLOW_DELAY));
        return true;
    }

    /** The nearest hostile mob in sight that is on fire and would not be hurt by water, or null. */
    private static Mob findBurningAlly(Witch witch) {
        List<Mob> found = witch.level().getEntitiesOfClass(Mob.class, witch.getBoundingBox().inflate(ALLY_RANGE),
                m -> m != witch && m instanceof Enemy && m.isAlive() && m.isOnFire() && !m.fireImmune()
                        && !m.isSensitiveToWater());
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob candidate : found) {
            double distance = witch.distanceTo(candidate);
            if (distance < ALLY_MIN || distance > ALLY_RANGE || distance >= bestDistance || !witch.hasLineOfSight(candidate)) {
                continue;
            }
            bestDistance = distance;
            best = candidate;
        }
        return best;
    }

    /** The follow-up to the water: a Splash Potion of Regeneration for an ally that is low on health. */
    private static void tryRegeneration(Witch witch, ServerLevel level, Mob ally) {
        if (!enabled(level) || witch.isDrinkingPotion() || !ally.isAlive() || ally.level() != level) {
            return;
        }
        if (ally.getHealth() > ally.getMaxHealth() * LOW_HEALTH || witch.getRandom().nextFloat() >= REGEN_CHANCE) {
            return;
        }
        double distance = witch.distanceTo(ally);
        if (distance < ALLY_MIN || distance > ALLY_RANGE || !witch.hasLineOfSight(ally)) {
            return;
        }
        Vec3 aim = ally.position().add(0.0, 0.5, 0.0);
        if (playerNear(level, aim)) {
            return;
        }
        ItemStack regen = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.REGENERATION);
        if (lob(witch, level, aim, regen, false)) {
            witch.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Is any player (creative ones too) close enough to be caught by a splash that lands here? */
    private static boolean playerNear(ServerLevel level, Vec3 at) {
        List<Player> near = new ArrayList<>();
        for (Player player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(at) <= PLAYER_CLEARANCE * PLAYER_CLEARANCE) {
                near.add(player);
            }
        }
        return !near.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------
    // Throwing
    // ---------------------------------------------------------------------------------------------------

    private static boolean lob(Witch witch, ServerLevel level, Vec3 aim, ItemStack stack, boolean milk) {
        throwingMilk = milk;
        try {
            return Siege.lobPotion(witch, level, aim, stack);
        } finally {
            throwingMilk = false;
        }
    }
}
