package com.example.curses.effect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.registry.CursesItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Incendiary: witches can throw Molotov Cocktails, and creepers set things on fire when they explode.
 *
 * <p><b>Molotovs:</b> {@link #MOLOTOV_CHANCE} of the harmful potions a witch throws at a player (Harming,
 * Poison, Slowness, Weakness, including Coven's long-range throws) become a Molotov Cocktail instead. It is a
 * normal thrown potion in flight that shows the molotov texture. When it hits something it shatters and places
 * fire on the ground around the impact (only where fire can survive, and only while mob griefing is on).</p>
 *
 * <p><b>Creepers:</b> one in three of the blocks a creeper's blast destroys is replaced by fire where it can
 * burn (the same rule vanilla fire explosions use). The fire is placed a tick after the blast, once the blocks
 * are really gone.</p>
 *
 * <p><b>Nothing is ever set alight directly.</b> Earlier versions called {@code igniteForSeconds} on everything
 * in a box around the impact or in the blast's list of affected entities. Those lists include entities behind
 * walls and well outside the visible blast, which caused "ghost fire" (players and mobs catching fire for no
 * visible reason). Now only real fire blocks exist, and entities catch fire from them exactly like in vanilla:
 * by standing in or touching a flame.</p>
 *
 * <p>Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class IncendiaryHandler {
    /** Chance for a harmful witch potion to be a molotov. */
    private static final float MOLOTOV_CHANCE = 0.4F;
    private static final int FIRE_RADIUS = 2;
    private static final float FIRE_CHANCE = 0.6F;
    /**
     * Volume of the glass-shatter sound. The server only sends a sound to players within 16 blocks x volume, and
     * witches throw from 24+ blocks (Coven), so at volume 1.0 many shatters were simply never sent to the player.
     */
    private static final float SHATTER_VOLUME = 2.5F;

    private record PendingFire(ServerLevel level, List<BlockPos> positions, long due) {
    }

    // Server thread only.
    private static final List<PendingFire> PENDING = new ArrayList<>();

    private IncendiaryHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.INCENDIARY);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Molotovs
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPotionJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ThrownPotion potion)) {
            return;
        }
        // Only potions thrown at players: buffs for other mobs are left alone.
        if (!(potion.getOwner() instanceof Witch witch) || !(witch.getTarget() instanceof Player) || !enabled(level)) {
            return;
        }
        ItemStack stack = potion.getItem();
        if (!stack.is(Items.SPLASH_POTION) && !stack.is(Items.LINGERING_POTION)) {
            return;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || !isHarmful(contents) || witch.getRandom().nextFloat() >= MOLOTOV_CHANCE) {
            return;
        }
        potion.setItem(new ItemStack(CursesItems.MOLOTOV.get()));
    }

    private static boolean isHarmful(PotionContents contents) {
        return contents.potion().map(p -> Potions.HARMING.equals(p) || Potions.POISON.equals(p)
                || Potions.SLOWNESS.equals(p) || Potions.WEAKNESS.equals(p)).orElse(false);
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.level() instanceof ServerLevel level)) {
            return;
        }
        if (!potion.getItem().is(CursesItems.MOLOTOV.get())) {
            return;
        }
        // Vanilla would treat this as a plain potion; handle the impact here instead.
        event.setCanceled(true);
        if (potion.isRemoved()) {
            return;
        }

        HitResult hit = event.getRayTraceResult();
        Vec3 at = hit.getLocation();
        BlockPos center = hit instanceof BlockHitResult block
                ? block.getBlockPos().relative(block.getDirection())
                : BlockPos.containing(at);
        shatter(level, at, center);
        potion.discard();
    }

    private static void shatter(ServerLevel level, Vec3 at, BlockPos center) {
        float pitch = 0.9F + level.getRandom().nextFloat() * 0.2F;
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SPLASH_POTION_BREAK, SoundSource.NEUTRAL, SHATTER_VOLUME, pitch);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.2, at.z, 30, 0.7, 0.4, 0.7, 0.03);

        // No entity is set alight here: only fire blocks are placed, and they burn whoever really touches them.
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }
        placeFire(level, center); // the impact point itself always gets a flame
        for (int dx = -FIRE_RADIUS; dx <= FIRE_RADIUS; dx++) {
            for (int dz = -FIRE_RADIUS; dz <= FIRE_RADIUS; dz++) {
                if (dx * dx + dz * dz > FIRE_RADIUS * FIRE_RADIUS + 1) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    if (level.getRandom().nextFloat() < FIRE_CHANCE) {
                        placeFire(level, center.offset(dx, dy, dz));
                    }
                }
            }
        }
    }

    /** Puts fire at pos if it is free and fire can survive there. */
    private static void placeFire(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (fire.canSurvive(level, pos)) {
            level.setBlockAndUpdate(pos, fire);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Creepers
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getExplosion().getDirectSourceEntity() instanceof Creeper)) {
            return;
        }
        if (!enabled(level)) {
            return;
        }

        // The blocks are only destroyed after this event, so remember where they were and light them next tick.
        // Entities are deliberately not ignited here (see class comment): they burn from the fire blocks only.
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (level.getRandom().nextInt(3) == 0) {
                spots.add(pos.immutable());
            }
        }
        if (!spots.isEmpty()) {
            PENDING.add(new PendingFire(level, spots, level.getGameTime() + 1));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || PENDING.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<PendingFire> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingFire pending = it.next();
            if (pending.level() != level || now < pending.due()) {
                continue;
            }
            it.remove();
            for (BlockPos pos : pending.positions()) {
                if (level.hasChunkAt(pos)) {
                    placeFire(level, pos);
                }
            }
        }
    }
}
