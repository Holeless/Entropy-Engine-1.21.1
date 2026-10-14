package com.example.curses.effect;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Coven: witches buff other mobs with splash potions, throw from much further away, and can throw lingering
 * potions.
 *
 * <ul>
 *   <li><b>Buffs</b>: while a player is near, a witch throws a splash potion at a hostile mob that is in a fight
 *       (Strength, Swiftness or Regeneration, or Healing / Regeneration for undead if it is below half health).
 *       Mobs that already have the effect are skipped, witches are not buffed, and nothing is thrown when a
 *       player is standing within 5 blocks of the ally (the splash would buff the player too).</li>
 *   <li><b>Range</b>: vanilla witches only throw from about 10 blocks. Now a witch also throws at its target
 *       from up to {@link #MAX_RANGE} blocks, on a lobbed arc worked out so the potion lands on the target
 *       (with a little lead on a moving player). The vanilla throw still happens up close.</li>
 *   <li><b>Lingering</b>: {@link #LINGER_CHANCE} of the potions a witch throws (vanilla's included) are
 *       lingering potions that leave a cloud behind.</li>
 * </ul>
 *
 * <p>Driven from a per-tick handler rather than an AI goal, so it never fights vanilla's witch goals over
 * movement. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class CovenHandler {
    private static final int CHECK_INTERVAL = 10;       // ticks between checks per witch
    private static final int BUFF_COOLDOWN = 120;       // ticks between buff throws per witch
    private static final int ATTACK_COOLDOWN = 60;      // ticks between long-range attack throws per witch
    private static final double BUFF_RANGE = 16.0;      // allies this close to the witch can be buffed
    private static final double MIN_BUFF_DIST = 3.0;
    private static final double PLAYER_CLEARANCE = 5.0; // no buff splash with a player this close to the ally
    private static final double PLAYER_NEAR = 32.0;     // buffs only happen while a player is this close
    private static final double VANILLA_RANGE = 9.5;    // up to here vanilla's own throw is used
    private static final double MAX_RANGE = 26.0;
    private static final float LINGER_CHANCE = 0.3F;
    private static final float BUFF_LINGER_CHANCE = 0.2F;
    private static final double THROW_SPEED = 1.3;

    private record Buff(Holder<Potion> potion, Holder<MobEffect> effect) {
    }

    private static final List<Buff> BUFFS = List.of(
            new Buff(Potions.STRENGTH, MobEffects.DAMAGE_BOOST),
            new Buff(Potions.SWIFTNESS, MobEffects.MOVEMENT_SPEED),
            new Buff(Potions.REGENERATION, MobEffects.REGENERATION));

    // Server thread only.
    private static final Map<Witch, Long> BUFF_READY = new WeakHashMap<>();
    private static final Map<Witch, Long> ATTACK_READY = new WeakHashMap<>();
    /** True while this handler adds its own potion, so the lingering conversion below leaves it alone. */
    private static boolean throwingOwn;

    private CovenHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.COVEN);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BUFF_READY.clear();
        ATTACK_READY.clear();
        throwingOwn = false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Lingering: some of vanilla's own witch throws turn into lingering potions
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPotionJoin(EntityJoinLevelEvent event) {
        if (throwingOwn || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ThrownPotion potion)) {
            return;
        }
        if (!(potion.getOwner() instanceof Witch witch) || !potion.getItem().is(Items.SPLASH_POTION) || !enabled(level)) {
            return;
        }
        if (witch.getRandom().nextFloat() >= LINGER_CHANCE) {
            return;
        }
        PotionContents contents = potion.getItem().get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return;
        }
        ItemStack lingering = new ItemStack(Items.LINGERING_POTION);
        lingering.set(DataComponents.POTION_CONTENTS, contents);
        potion.setItem(lingering);
    }

    // ---------------------------------------------------------------------------------------------------
    // Buffing allies and long-range throws
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onWitchTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Witch witch) || witch.level().isClientSide()) {
            return;
        }
        if (!witch.isAlive() || witch.isDrinkingPotion() || (witch.tickCount + witch.getId()) % CHECK_INTERVAL != 0) {
            return;
        }
        ServerLevel level = (ServerLevel) witch.level();
        if (!enabled(level)) {
            return;
        }
        long now = level.getGameTime();
        if (!tryAttack(witch, level, now)) {
            tryBuff(witch, level, now);
        }
    }

    /** Throws at the witch's target from beyond vanilla's range. Returns true if it threw. */
    private static boolean tryAttack(Witch witch, ServerLevel level, long now) {
        if (!(witch.getTarget() instanceof Player target) || !target.isAlive() || target.isSpectator() || target.isCreative()) {
            return false;
        }
        Long ready = ATTACK_READY.get(witch);
        if (ready != null && now < ready) {
            return false;
        }
        double dx = target.getX() - witch.getX();
        double dz = target.getZ() - witch.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= VANILLA_RANGE || distance > MAX_RANGE || !witch.hasLineOfSight(target)) {
            return false;
        }

        // Same choice vanilla makes, minus the raider healing and the point-blank weakness.
        Holder<Potion> potion = Potions.HARMING;
        if (!target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
            potion = Potions.SLOWNESS;
        } else if (target.getHealth() >= 8.0F && !target.hasEffect(MobEffects.POISON)) {
            potion = Potions.POISON;
        }
        boolean lingering = witch.getRandom().nextFloat() < LINGER_CHANCE;

        Vec3 aim = target.position().add(0.0, 0.3, 0.0);
        Vec3 motion = target.getDeltaMovement();
        double flight = distance / (THROW_SPEED * 0.85);
        aim = aim.add(motion.x * flight * 0.5, 0.0, motion.z * flight * 0.5);

        if (!throwPotion(witch, level, aim, potion, lingering)) {
            return false;
        }
        ATTACK_READY.put(witch, now + ATTACK_COOLDOWN);
        return true;
    }

    private static void tryBuff(Witch witch, ServerLevel level, long now) {
        Long ready = BUFF_READY.get(witch);
        if (ready != null && now < ready) {
            return;
        }
        if (level.getNearestPlayer(witch.getX(), witch.getY(), witch.getZ(), PLAYER_NEAR, false) == null) {
            return;
        }

        List<Mob> allies = level.getEntitiesOfClass(Mob.class, witch.getBoundingBox().inflate(BUFF_RANGE),
                m -> m != witch && m.isAlive() && m instanceof Enemy && !(m instanceof Witch)
                        && m.getTarget() != null && m.getTarget().isAlive());
        allies.sort((a, b) -> Double.compare(witch.distanceToSqr(a), witch.distanceToSqr(b)));

        for (Mob ally : allies) {
            if (witch.distanceToSqr(ally) < MIN_BUFF_DIST * MIN_BUFF_DIST || !witch.hasLineOfSight(ally)) {
                continue;
            }
            if (level.getNearestPlayer(ally.getX(), ally.getY(), ally.getZ(), PLAYER_CLEARANCE, false) != null) {
                continue;
            }
            Holder<Potion> potion = pickBuff(ally);
            if (potion == null) {
                continue;
            }
            boolean lingering = witch.getRandom().nextFloat() < BUFF_LINGER_CHANCE;
            if (throwPotion(witch, level, ally.position().add(0.0, 0.3, 0.0), potion, lingering)) {
                BUFF_READY.put(witch, now + BUFF_COOLDOWN);
                return;
            }
        }
    }

    /** What this ally should be given, or null if it has everything. Healing hurts the undead, so they get Regeneration. */
    private static Holder<Potion> pickBuff(LivingEntity ally) {
        if (ally.getHealth() < ally.getMaxHealth() * 0.5F && !ally.hasEffect(MobEffects.REGENERATION)) {
            return ally.getType().is(EntityTypeTags.UNDEAD) ? Potions.REGENERATION : Potions.HEALING;
        }
        List<Buff> missing = BUFFS.stream().filter(b -> !ally.hasEffect(b.effect())).toList();
        if (missing.isEmpty()) {
            return null;
        }
        return missing.get(ally.getRandom().nextInt(missing.size())).potion();
    }

    // ---------------------------------------------------------------------------------------------------
    // Throwing
    // ---------------------------------------------------------------------------------------------------

    /** Lobs a potion so it lands at {@code aim}. Returns false if that spot cannot be reached. */
    private static boolean throwPotion(Witch witch, ServerLevel level, Vec3 aim, Holder<Potion> potion, boolean lingering) {
        Item item = lingering ? Items.LINGERING_POTION : Items.SPLASH_POTION;
        ThrownPotion thrown = new ThrownPotion(level, witch);
        thrown.setItem(PotionContents.createItemStack(item, potion));

        Vec3 from = thrown.position();
        double dx = aim.x - from.x;
        double dz = aim.z - from.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) {
            return false;
        }
        double angle = findAngle(distance, aim.y - from.y);
        if (Double.isNaN(angle)) {
            return false;
        }

        double cos = Math.cos(angle);
        thrown.shoot(dx / distance * cos, Math.sin(angle), dz / distance * cos, (float) THROW_SPEED, 1.0F);
        witch.playSound(SoundEvents.WITCH_THROW, 1.0F, 0.8F + witch.getRandom().nextFloat() * 0.4F);
        throwingOwn = true;
        try {
            level.addFreshEntity(thrown);
        } finally {
            throwingOwn = false;
        }
        return true;
    }

    /**
     * The launch angle (radians) at which a potion fired at {@link #THROW_SPEED} comes down {@code distance}
     * blocks away and {@code dy} blocks above where it started. Flies the potion with the game's own rules
     * (move, 1% drag, 0.05 gravity) for every whole degree and keeps the closest landing, preferring the
     * flatter shot. NaN if nothing lands within about 2 blocks of the spot.
     */
    private static double findAngle(double distance, double dy) {
        double best = Double.NaN;
        double bestError = 2.0;
        for (int degrees = -10; degrees <= 60; degrees++) {
            double angle = Math.toRadians(degrees);
            double vx = THROW_SPEED * Math.cos(angle);
            double vy = THROW_SPEED * Math.sin(angle);
            double x = 0.0;
            double y = 0.0;
            for (int tick = 0; tick < 100; tick++) {
                double px = x;
                double py = y;
                x += vx;
                y += vy;
                vx *= 0.99;
                vy = vy * 0.99 - 0.05;
                if (py > dy && y <= dy) {
                    double landed = px + (x - px) * ((py - dy) / (py - y));
                    double error = Math.abs(landed - distance);
                    if (error < bestError) {
                        bestError = error;
                        best = angle;
                    }
                    break;
                }
            }
        }
        return best;
    }
}
