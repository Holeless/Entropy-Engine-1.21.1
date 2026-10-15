package com.example.curses.effect;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Kiting: ranged mobs back away from you while they attack, and hide behind cover between shots.
 *
 * <p><b>Who:</b> hostile mobs that shoot or throw (skeletons, strays, boggeds, pillagers, illusioners, witches,
 * drowned with a trident). Piglins (their brain already backs up) and the Wither are left alone. Every one of
 * them gets two extra goals when it joins the world, which do nothing while the curse is off:</p>
 * <ul>
 *   <li>{@link KiteGoal}: while the mob is attacking and you are closer than its kiting range, it walks
 *       <i>backwards</i> away from you while still facing you, so it keeps drawing and shooting as it retreats.
 *       It runs alongside the vanilla attack goal (it takes no goal flags), so the shooting is untouched.</li>
 *   <li>{@link HideGoal}: right after the mob fires, it runs to a spot where you cannot see it, behind blocks or
 *       behind other mobs, waits there for a second or two, then comes back out for the next shot.</li>
 * </ul>
 *
 * <p>Shots are noticed by watching for projectiles that join the world with such a mob as their owner. Server
 * only. Spiders carrying a ranged rider use the same backpedal, see {@link JockeyKiteGoal}.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class KitingHandler {
    /**
     * Share of a mob's normal walking pace it backs away at (1.0 = exactly as fast as it walks, which is the pace
     * a mob runs away at).
     */
    public static final double BACKPEDAL_SPEED = 1.0;

    // Server thread only.
    /** Game time of each mob's last shot. */
    static final Map<Mob, Long> LAST_SHOT = new WeakHashMap<>();
    /** Mobs that are currently running to or waiting in cover (the kite goal stays out of their way). */
    static final Set<Mob> HIDING = Collections.newSetFromMap(new WeakHashMap<>());

    private KitingHandler() {
    }

    static boolean enabled(Level level) {
        return level instanceof ServerLevel serverLevel && CurseData.isActive(serverLevel.getServer(), Curses.KITING);
    }

    static boolean isEligible(Mob mob) {
        return mob instanceof Enemy && mob instanceof RangedAttackMob
                && !(mob instanceof AbstractPiglin) && !(mob instanceof WitherBoss);
    }

    /** Is the mob holding something it shoots or throws right now? (A skeleton with no bow just fights in melee.) */
    static boolean isArmed(Mob mob) {
        if (mob instanceof Witch witch) {
            return !witch.isDrinkingPotion();
        }
        Item item = mob.getMainHandItem().getItem();
        return item instanceof ProjectileWeaponItem || item instanceof TridentItem;
    }

    /**
     * Closer than this, the mob backs away. Well inside the range its own attack goal works at (bows 15 blocks,
     * crossbows 8, witches and tridents 10), so it only retreats when you are right on top of it and keeps shooting:
     * bows 7 blocks, crossbows 4, everything else 5.
     */
    static double kiteRange(Mob mob) {
        Item item = mob.getMainHandItem().getItem();
        if (item instanceof CrossbowItem) {
            return 4.0;
        }
        if (item instanceof BowItem) {
            return 7.0;
        }
        return 5.0;
    }

    /** The range a mob's own attack goal shoots at: bows 15 blocks, crossbows 8, everything else 10. */
    static double fireRange(Mob mob) {
        Item item = mob.getMainHandItem().getItem();
        if (item instanceof CrossbowItem) {
            return 8.0;
        }
        if (item instanceof BowItem) {
            return 15.0;
        }
        return 10.0;
    }

    /** A spot a mob of this height can stand on: free body space, solid floor, nothing that hurts. */
    static boolean standable(Level level, BlockPos feet, int height) {
        for (int i = 0; i < height; i++) {
            BlockPos pos = feet.above(i);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() || !level.getFluidState(pos).isEmpty() || state.is(BlockTags.FIRE)) {
                return false;
            }
        }
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        if (floor.getCollisionShape(level, below).isEmpty() || !floor.getFluidState().isEmpty()) {
            return false;
        }
        return !(floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS) || floor.is(BlockTags.CAMPFIRES));
    }

    @SubscribeEvent
    public static void onMobJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || !isEligible(mob)) {
            return;
        }
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof KiteGoal) {
                return;
            }
        }
        // Hide at priority 1: while it runs, the vanilla attack goal (same MOVE flag, lower priority) is paused.
        mob.goalSelector.addGoal(1, new HideGoal(mob));
        // Kite takes no flags, so it runs next to the attack goal. Added last on purpose: goals tick in the
        // order they were added, so its strafing is the last word each tick.
        mob.goalSelector.addGoal(3, new KiteGoal(mob));
    }

    /** Notes every shot: a projectile entering the world with one of these mobs as its owner. */
    @SubscribeEvent
    public static void onProjectileJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Projectile projectile)) {
            return;
        }
        if (!(projectile.getOwner() instanceof Mob mob) || !isEligible(mob) || !enabled(level)) {
            return;
        }
        LAST_SHOT.put(mob, level.getGameTime());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SHOT.clear();
        HIDING.clear();
    }
}
