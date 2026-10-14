package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Warping: undead mobs and piglins have a chance to spawn with an Ender Pearl in their off hand, and use it to
 * get to you.
 *
 * <p><b>Who can get one:</b> every undead mob except phantoms, skeleton/zombie horses and the Wither, plus
 * piglins and piglin brutes ({@code PiglinAiMixin} stops piglins throwing the pearl away). Each mob rolls once
 * (remembered in its saved data) and only gets a pearl if its off hand is empty. The pearl is not loot.</p>
 *
 * <p><b>How they use it:</b> a mob holding a pearl whose target is a player between {@link #MIN_RANGE} and
 * {@link #MAX_RANGE} blocks away, in sight, throws it at them (checked twice a second, {@link #THROW_CHANCE}
 * each time). A thrown pearl teleports its thrower to where it lands, so the mob appears next to the player.
 * The pearl is used up; a mob throws at most one.</p>
 *
 * <p>Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class WarpingHandler {
    /** Chance for each eligible mob to get an ender pearl. */
    public static final float CHANCE = 0.10F;

    private static final String KEY_ROLLED = "curses_warping_rolled";

    private static final int CHECK_INTERVAL = 10;       // ticks between throw decisions
    private static final double MIN_RANGE = 10.0;       // closer than this it simply walks
    private static final double MAX_RANGE = 32.0;
    private static final float THROW_CHANCE = 0.3F;     // per check, while a throw is possible
    private static final float THROW_SPEED = 1.6F;

    private WarpingHandler() {
    }

    private static boolean isEligible(Mob mob) {
        if (mob instanceof Phantom || mob instanceof AbstractHorse || mob instanceof WitherBoss) {
            return false;
        }
        return mob.getType().is(EntityTypeTags.UNDEAD) || mob instanceof AbstractPiglin;
    }

    // ---------------------------------------------------------------------------------------------------
    // Spawning
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob) || !isEligible(mob)) {
            return;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(KEY_ROLLED) || !CurseData.isActive(level.getServer(), Curses.WARPING)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);
        if (mob.getRandom().nextFloat() >= CHANCE || !mob.getOffhandItem().isEmpty()) {
            return;
        }

        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ENDER_PEARL));
        mob.setDropChance(EquipmentSlot.OFFHAND, 0.0F); // the curse's pearl is not loot
        CursesMod.LOGGER.debug("Warping: gave an ender pearl to {} at {}", mob.getType(), mob.blockPosition());
    }

    // ---------------------------------------------------------------------------------------------------
    // Throwing
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onMobTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        if (mob.tickCount % CHECK_INTERVAL != 0 || !mob.isAlive() || !mob.getOffhandItem().is(Items.ENDER_PEARL)) {
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        if (!CurseData.isActive(level.getServer(), Curses.WARPING)) {
            return;
        }
        // Drawing a bow or similar: finish that first.
        if (mob.isPassenger() || mob.isUsingItem()) {
            return;
        }

        if (!(targetOf(mob) instanceof Player target) || !target.isAlive() || target.isSpectator() || target.isCreative()
                || target.level() != mob.level()) {
            return;
        }
        double distSqr = mob.distanceToSqr(target);
        if (distSqr < MIN_RANGE * MIN_RANGE || distSqr > MAX_RANGE * MAX_RANGE || !mob.hasLineOfSight(target)) {
            return;
        }
        if (mob.getRandom().nextFloat() >= THROW_CHANCE) {
            return;
        }
        throwPearl(mob, level, target);
    }

    private static void throwPearl(Mob mob, ServerLevel level, Player target) {
        ThrownEnderpearl pearl = new ThrownEnderpearl(level, mob);
        double dx = target.getX() - mob.getX();
        double dy = target.getY(0.5) - pearl.getY();
        double dz = target.getZ() - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // A little extra lift to make up for the pearl's drop over the distance.
        pearl.shoot(dx, dy + horizontal * 0.08, dz, THROW_SPEED, 1.0F);

        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY); // used up
        mob.swing(InteractionHand.OFF_HAND);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE,
                0.5F, 0.4F / (mob.getRandom().nextFloat() * 0.4F + 0.8F));
        level.addFreshEntity(pearl);
        CursesMod.LOGGER.debug("Warping: {} threw an ender pearl at {} blocks", mob.getType(), (int) Math.sqrt(mob.distanceToSqr(target)));
    }

    /** The mob's attack target. Piglins keep theirs in the brain instead of the usual target field. */
    private static LivingEntity targetOf(Mob mob) {
        LivingEntity target = mob.getTarget();
        // Only piglins have this memory registered; asking any other brain for it throws.
        if (target == null && mob instanceof AbstractPiglin
                && mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        }
        return target;
    }
}
