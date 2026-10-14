package com.example.curses.effect;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Bastion: zombies, skeletons and piglins have a 15% chance to spawn with a shield in their off hand, and they
 * use it the way a player would.
 *
 * <p><b>Who can get one:</b> only mobs that visibly hold items in their hands: zombies (husks, drowned,
 * zombie villagers and zombified piglins included), skeletons (strays, wither skeletons, boggeds) and piglins
 * (brutes too). Mobs with crossed arms (villagers, wandering traders, illagers, witches) and mobs that cannot
 * hold anything (golems, phantoms, horses, zoglins...) never get one, see {@link #canHoldShield}. Each mob rolls
 * once (remembered in its saved data). A mob that is already holding something in its off hand, or that
 * carries a bow, crossbow or trident (those need the same "using item" state as a shield and would fight
 * over it), does not get one. The shield is not dropped on death.</p>
 *
 * <p><b>How they use it:</b> vanilla already blocks with any living entity's raised shield (the frontal
 * arc check, the block sound, arrows bouncing off), the mobs just never raise one. This handler does, with the
 * same rules a player's shield follows:</p>
 * <ul>
 *   <li>With a target within {@link #GUARD_RANGE} blocks and in sight, the mob reacts after a moment, raises
 *       the shield for 1 to 2 seconds, then lowers it for 2.5 to 4.5 seconds so the player gets a clear opening.</li>
 *   <li>If the target is drawing a bow, throwing a trident or holding a loaded crossbow, the mob guards from
 *       up to {@link #RANGED_GUARD_RANGE} blocks away, but only notices it about 30% of the time.</li>
 *   <li>A mob with its shield up takes no knockback at all (so it does not hop when hit).</li>
 *   <li>An axe hit on a mob whose shield is up and facing the attacker disables it, as in vanilla: that hit is
 *       still blocked, then the shield drops and cannot be raised again for 5 seconds.</li>
 * </ul>
 *
 * <p>Server only. Piglins keep the shield because {@code PiglinAiMixin} stops them throwing away their
 * off-hand item.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class BastionHandler {
    /** Chance for each eligible mob to get a shield. */
    public static final float CHANCE = 0.15F;

    private static final String KEY_ROLLED = "curses_bastion_rolled";

    private static final int CHECK_INTERVAL = 2;           // ticks between guard decisions
    private static final double GUARD_RANGE = 4.0;         // only guard when the target is about in melee range
    private static final double RANGED_GUARD_RANGE = 16.0; // ... or this close if the target is aiming something
    private static final float RANGED_REACT_CHANCE = 0.3F; // a mob only sometimes notices an aimed bow
    private static final int REACT_MIN = 10;               // ticks it takes the mob to react before the shield goes up
    private static final int REACT_MAX = 25;
    private static final int HOLD_MIN = 20;                // ticks the shield stays up
    private static final int HOLD_MAX = 45;
    private static final int REST_MIN = 50;                // ticks it stays down afterwards (the player's opening)
    private static final int REST_MAX = 90;
    private static final int DISABLE_TICKS = 100;          // an axe hit disables the shield for this long

    // Server thread only.
    private static final Map<Mob, Long> NEXT_CHANGE = new WeakHashMap<>();
    private static final Map<Mob, Long> DISABLED_UNTIL = new WeakHashMap<>();
    private static final Map<Mob, Long> RAISE_AT = new WeakHashMap<>();

    private BastionHandler() {
    }

    private static boolean isEligible(Mob mob) {
        return (mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof AbstractPiglin)
                && canHoldShield(mob);
    }

    /**
     * Can this mob visibly hold a shield? Mobs with crossed arms (villagers, traders, illagers, witches) and
     * golems cannot: their model does not show the item in hand and a raised shield would make no sense.
     */
    private static boolean canHoldShield(Mob mob) {
        return !(mob instanceof AbstractVillager
                || mob instanceof AbstractIllager
                || mob instanceof Witch
                || mob instanceof AbstractGolem);
    }

    /** Bows, crossbows and tridents use the same "using item" state as a shield, so they cannot be combined. */
    private static boolean isRanged(ItemStack stack) {
        return stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem;
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
        if (data.getBoolean(KEY_ROLLED) || !CurseData.isActive(level.getServer(), Curses.BASTION)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);
        if (mob.getRandom().nextFloat() >= CHANCE) {
            return;
        }
        if (!mob.getOffhandItem().isEmpty() || isRanged(mob.getMainHandItem())) {
            return;
        }

        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        mob.setDropChance(EquipmentSlot.OFFHAND, 0.0F); // the curse's shield is not loot
        CursesMod.LOGGER.debug("Bastion: gave a shield to {} at {}", mob.getType(), mob.blockPosition());
    }

    // ---------------------------------------------------------------------------------------------------
    // Using the shield
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onMobTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        if (mob.tickCount % CHECK_INTERVAL != 0 || !mob.isAlive() || !mob.getOffhandItem().is(Items.SHIELD)) {
            return;
        }
        if (!canHoldShield(mob)) {
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        if (!CurseData.isActive(level.getServer(), Curses.BASTION)) {
            lower(mob);
            return;
        }
        // Drawing a bow or similar: that wins, the shield stays out of it.
        if (mob.isUsingItem() && mob.getUsedItemHand() != InteractionHand.OFF_HAND) {
            return;
        }

        long now = level.getGameTime();
        boolean raised = isRaised(mob);
        Long disabled = DISABLED_UNTIL.get(mob);
        if ((disabled != null && now < disabled) || isRanged(mob.getMainHandItem())) {
            if (raised) {
                lower(mob);
            }
            return;
        }

        LivingEntity target = targetOf(mob);
        boolean threatened = isThreatened(mob, target);
        Long next = NEXT_CHANGE.get(mob);
        boolean ready = next == null || now >= next;

        if (raised) {
            RAISE_AT.remove(mob);
            if (!threatened) {
                lower(mob);
                NEXT_CHANGE.remove(mob);
            } else if (ready) {
                lower(mob);
                NEXT_CHANGE.put(mob, now + mob.getRandom().nextIntBetweenInclusive(REST_MIN, REST_MAX));
            }
            return;
        }

        if (!threatened) {
            RAISE_AT.remove(mob);
            return;
        }
        if (!ready) {
            return;
        }
        // Not the fastest thinker: it takes a moment to react, and a bow aimed from afar is often ignored.
        Long raiseAt = RAISE_AT.get(mob);
        if (raiseAt == null) {
            if (mob.distanceToSqr(target) > GUARD_RANGE * GUARD_RANGE && mob.getRandom().nextFloat() >= RANGED_REACT_CHANCE) {
                NEXT_CHANGE.put(mob, now + 40);
                return;
            }
            RAISE_AT.put(mob, now + mob.getRandom().nextIntBetweenInclusive(REACT_MIN, REACT_MAX));
            return;
        }
        if (now >= raiseAt) {
            RAISE_AT.remove(mob);
            mob.startUsingItem(InteractionHand.OFF_HAND);
            NEXT_CHANGE.put(mob, now + mob.getRandom().nextIntBetweenInclusive(HOLD_MIN, HOLD_MAX));
        }
    }

    /** Shield actually up (vanilla's isBlocking() only turns true a few ticks after raising, so it is not used). */
    private static boolean isRaised(Mob mob) {
        return mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND && mob.getOffhandItem().is(Items.SHIELD);
    }

    private static void lower(Mob mob) {
        if (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND) {
            mob.stopUsingItem();
        }
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

    private static boolean isThreatened(Mob mob, LivingEntity target) {
        if (target == null || !target.isAlive() || target.level() != mob.level()) {
            return false;
        }
        double distSqr = mob.distanceToSqr(target);
        if (distSqr <= GUARD_RANGE * GUARD_RANGE) {
            return mob.hasLineOfSight(target);
        }
        if (distSqr <= RANGED_GUARD_RANGE * RANGED_GUARD_RANGE && isAimingRanged(target)) {
            return mob.hasLineOfSight(target);
        }
        return false;
    }

    private static boolean isAimingRanged(LivingEntity target) {
        if (target.isUsingItem()) {
            Object item = target.getUseItem().getItem();
            if (item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem) {
                return true;
            }
        }
        ItemStack held = target.getMainHandItem();
        return held.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(held);
    }

    // ---------------------------------------------------------------------------------------------------
    // Axes beat shields
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || !canHoldShield(mob)) {
            return;
        }
        if (!isRaised(mob)) {
            return;
        }
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)
                || !(source.getDirectEntity() instanceof LivingEntity attacker)
                || !(attacker.getMainHandItem().getItem() instanceof AxeItem)
                || !isInFront(mob, source)) {
            return;
        }

        // Like vanilla's axe vs. shield: this hit is still blocked, then the shield drops (the next guard
        // check lowers it) and stays down for DISABLE_TICKS.
        DISABLED_UNTIL.put(mob, mob.level().getGameTime() + DISABLE_TICKS);
        mob.playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + mob.level().random.nextFloat() * 0.4F);
    }

    /**
     * A mob with its shield up is not pushed around, and above all does not hop: vanilla knockback always
     * gives an on-ground mob an upward kick, so any knockback is cancelled while the shield is raised.
     */
    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || !canHoldShield(mob)) {
            return;
        }
        if (isRaised(mob)) {
            event.setCanceled(true);
        }
    }

    /** Same frontal-arc test vanilla uses to decide whether a raised shield catches a hit. */
    private static boolean isInFront(Mob mob, DamageSource source) {
        Vec3 from = source.getSourcePosition();
        if (from == null) {
            return false;
        }
        Vec3 view = mob.calculateViewVector(0.0F, mob.getYHeadRot());
        Vec3 away = from.vectorTo(mob.position());
        away = new Vec3(away.x, 0.0, away.z).normalize();
        return away.dot(view) < 0.0;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT_CHANGE.clear();
        DISABLED_UNTIL.clear();
        RAISE_AT.clear();
    }
}
