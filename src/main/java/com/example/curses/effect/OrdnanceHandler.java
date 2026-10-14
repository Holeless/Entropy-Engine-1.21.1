package com.example.curses.effect;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.entity.ExplosiveArrow;
import com.example.curses.registry.CursesEntities;
import com.example.curses.registry.CursesItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Ordnance: skeletons carry exploding arrows and witches throw dynamite, and both use them to blast their way
 * to a player who has walled themselves in.
 *
 * <p><b>Skeletons</b> (skeletons, strays and boggeds, anything that shoots a bow): each rolls once at spawn
 * (remembered in its saved data), and {@link #ARMED_CHANCE} of them carry 1-3 exploding arrows. An armed skeleton
 * turns {@link #ARROW_CHANCE} of its normal shots into an exploding arrow while it has some left, and if a player
 * is close but the skeleton cannot walk to them (a house, a pillar), it fires its exploding arrows at them on
 * purpose, one every few seconds, even with no line of sight, so they burst on the walls. A stray's slowness
 * arrow is swapped for the exploding one too.</p>
 *
 * <p><b>Witches:</b> {@link #DYNAMITE_CHANCE} of the harmful potions a witch throws at a player become a stick of
 * dynamite (a thrown potion showing the dynamite texture that explodes on impact, like the Molotov of
 * Incendiary). And a witch that cannot walk to a nearby player lobs dynamite at them every few seconds, whether
 * or not it can see them, to blow a way in.</p>
 *
 * <p>Both explosions only break blocks while mob griefing is on. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class OrdnanceHandler {
    private static final String KEY_ROLLED = "curses_ordnance_rolled";
    private static final String KEY_ARROWS = "curses_ordnance_arrows";

    /** Chance for a skeleton to carry exploding arrows. */
    private static final float ARMED_CHANCE = 0.3F;
    /** Chance for a normal shot of an armed skeleton to be an exploding arrow. */
    private static final float ARROW_CHANCE = 0.4F;
    /** Chance for a harmful witch potion to be dynamite. */
    private static final float DYNAMITE_CHANCE = 0.3F;
    private static final float DYNAMITE_POWER = 2.0F;

    private static final int SIEGE_CHECK = 20;           // ticks between siege checks per mob
    private static final int SKELETON_COOLDOWN = 60;     // ticks between deliberate arrows
    private static final int WITCH_COOLDOWN = 100;       // ticks between deliberate dynamite throws
    private static final double MIN_SKELETON_DIST = 3.0;
    private static final double MIN_WITCH_DIST = 4.0;    // the blast must not catch the thrower

    // Server thread only.
    private static final Map<AbstractSkeleton, Long> SKELETON_READY = new WeakHashMap<>();
    private static final Map<Witch, Long> WITCH_READY = new WeakHashMap<>();

    private OrdnanceHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.ORDNANCE);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SKELETON_READY.clear();
        WITCH_READY.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Skeletons: who carries exploding arrows
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onSkeletonJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof AbstractSkeleton skeleton)) {
            return;
        }
        CompoundTag data = skeleton.getPersistentData();
        if (data.getBoolean(KEY_ROLLED) || !enabled(level)) {
            return;
        }
        data.putBoolean(KEY_ROLLED, true);
        if (skeleton.getRandom().nextFloat() < ARMED_CHANCE) {
            data.putInt(KEY_ARROWS, 1 + skeleton.getRandom().nextInt(3));
        }
    }

    /** Some of an armed skeleton's normal shots are swapped, as they are fired, for an exploding arrow. */
    @SubscribeEvent
    public static void onArrowJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Arrow arrow)) {
            return;
        }
        if (!(arrow.getOwner() instanceof AbstractSkeleton skeleton) || !enabled(level)) {
            return;
        }
        CompoundTag data = skeleton.getPersistentData();
        int arrows = data.getInt(KEY_ARROWS);
        if (arrows <= 0 || skeleton.getRandom().nextFloat() >= ARROW_CHANCE) {
            return;
        }
        data.putInt(KEY_ARROWS, arrows - 1);

        ExplosiveArrow bolt = new ExplosiveArrow(CursesEntities.EXPLOSIVE_ARROW.get(), level);
        bolt.setOwner(skeleton);
        bolt.moveTo(arrow.getX(), arrow.getY(), arrow.getZ(), arrow.getYRot(), arrow.getXRot());
        bolt.setDeltaMovement(arrow.getDeltaMovement());
        bolt.pickup = AbstractArrow.Pickup.DISALLOWED;

        // Replace the arrow the skeleton just fired. The new entity is not an Arrow, so it is not seen here again.
        event.setCanceled(true);
        level.addFreshEntity(bolt);
        CursesMod.LOGGER.debug("Ordnance: {} at {} shot an explosive arrow ({} left)", skeleton.getType(), skeleton.blockPosition(), arrows - 1);
    }

    // ---------------------------------------------------------------------------------------------------
    // Skeletons: blasting into a shelter
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onSkeletonTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractSkeleton skeleton) || skeleton.level().isClientSide()) {
            return;
        }
        if (!skeleton.isAlive() || (skeleton.tickCount + skeleton.getId()) % SIEGE_CHECK != 0) {
            return;
        }
        CompoundTag data = skeleton.getPersistentData();
        int arrows = data.getInt(KEY_ARROWS);
        // Needs a bow in hand and must not be in the middle of drawing it.
        if (arrows <= 0 || !(skeleton.getMainHandItem().getItem() instanceof BowItem) || skeleton.isUsingItem()) {
            return;
        }
        ServerLevel level = (ServerLevel) skeleton.level();
        if (!enabled(level)) {
            return;
        }
        long now = level.getGameTime();
        Long ready = SKELETON_READY.get(skeleton);
        if (ready != null && now < ready) {
            return;
        }

        Player victim = Siege.sealedPlayer(skeleton, level, MIN_SKELETON_DIST);
        if (victim == null) {
            return;
        }
        shootAt(skeleton, level, victim);
        data.putInt(KEY_ARROWS, arrows - 1);
        SKELETON_READY.put(skeleton, now + SKELETON_COOLDOWN);
        CursesMod.LOGGER.debug("Ordnance: {} at {} fired an explosive arrow at a sealed-in player ({} left)",
                skeleton.getType(), skeleton.blockPosition(), arrows - 1);
    }

    private static void shootAt(AbstractSkeleton skeleton, ServerLevel level, Player victim) {
        ExplosiveArrow bolt = new ExplosiveArrow(CursesEntities.EXPLOSIVE_ARROW.get(), level);
        bolt.setOwner(skeleton);
        bolt.setPos(skeleton.getX(), skeleton.getEyeY() - 0.1, skeleton.getZ());
        bolt.pickup = AbstractArrow.Pickup.DISALLOWED;

        double dx = victim.getX() - skeleton.getX();
        double dy = victim.getY(0.5) - bolt.getY();
        double dz = victim.getZ() - skeleton.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Lifted a little to make up for the drop over the distance, like vanilla skeletons do.
        bolt.shoot(dx, dy + horizontal * 0.2, dz, 1.6F, 2.0F);

        skeleton.getLookControl().setLookAt(victim);
        skeleton.swing(InteractionHand.MAIN_HAND);
        skeleton.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (skeleton.getRandom().nextFloat() * 0.4F + 0.8F));
        level.addFreshEntity(bolt);
    }

    // ---------------------------------------------------------------------------------------------------
    // Dynamite
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
        if (contents == null || !isHarmful(contents) || witch.getRandom().nextFloat() >= DYNAMITE_CHANCE) {
            return;
        }
        potion.setItem(new ItemStack(CursesItems.DYNAMITE.get()));
        level.playSound(null, potion.getX(), potion.getY(), potion.getZ(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 1.0F);
    }

    private static boolean isHarmful(PotionContents contents) {
        return contents.potion().map(p -> Potions.HARMING.equals(p) || Potions.POISON.equals(p)
                || Potions.SLOWNESS.equals(p) || Potions.WEAKNESS.equals(p)).orElse(false);
    }

    /** A witch that cannot walk to a nearby player lobs dynamite at them, to blow a way in. */
    @SubscribeEvent
    public static void onWitchTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Witch witch) || witch.level().isClientSide()) {
            return;
        }
        if (!witch.isAlive() || witch.isDrinkingPotion() || (witch.tickCount + witch.getId()) % SIEGE_CHECK != 0) {
            return;
        }
        ServerLevel level = (ServerLevel) witch.level();
        if (!enabled(level)) {
            return;
        }
        long now = level.getGameTime();
        Long ready = WITCH_READY.get(witch);
        if (ready != null && now < ready) {
            return;
        }

        Player victim = Siege.sealedPlayer(witch, level, MIN_WITCH_DIST);
        if (victim == null) {
            return;
        }
        Vec3 aim = victim.position().add(0.0, 1.0, 0.0);
        if (Siege.lobPotion(witch, level, aim, new ItemStack(CursesItems.DYNAMITE.get()))) {
            level.playSound(null, witch.getX(), witch.getY(), witch.getZ(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 1.0F);
            WITCH_READY.put(witch, now + WITCH_COOLDOWN);
            CursesMod.LOGGER.debug("Ordnance: witch at {} threw dynamite at a sealed-in player", witch.blockPosition());
        }
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.level() instanceof ServerLevel level)) {
            return;
        }
        if (!potion.getItem().is(CursesItems.DYNAMITE.get())) {
            return;
        }
        // Vanilla would treat this as a plain potion; handle the impact here instead.
        event.setCanceled(true);
        if (potion.isRemoved()) {
            return;
        }

        Vec3 at = event.getRayTraceResult().getLocation();
        level.explode(potion, at.x, at.y, at.z, DYNAMITE_POWER, Level.ExplosionInteraction.MOB);
        potion.discard();
    }
}
