package com.example.curses.effect;

import com.example.curses.CursesMod;
import com.example.curses.curse.CurseData;
import com.example.curses.curse.Curses;
import com.example.curses.entity.ExplosiveArrow;
import com.example.curses.registry.CursesEntities;
import com.example.curses.registry.CursesItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownPotion;
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

/**
 * Ordnance: skeletons sometimes shoot an exploding arrow, and witches sometimes throw dynamite.
 *
 * <p><b>Arrows:</b> {@link #ARROW_CHANCE} of the arrows a plain skeleton shoots are swapped, as they are
 * fired, for an {@link ExplosiveArrow} with the same position, direction and speed. It explodes where it hits
 * and is never picked up. Strays and boggeds are left alone because their arrows carry potion effects.</p>
 *
 * <p><b>Dynamite:</b> {@link #DYNAMITE_CHANCE} of the harmful potions a witch throws at a player become a stick
 * of dynamite instead. It is a normal thrown potion in flight showing the dynamite texture, and it explodes
 * on impact (same pattern as the Molotov Cocktail of Incendiary).</p>
 *
 * <p>Both explosions only break blocks while mob griefing is on. Server only.</p>
 */
@EventBusSubscriber(modid = CursesMod.MOD_ID)
public final class OrdnanceHandler {
    /** Chance for a plain skeleton's arrow to be an explosive one. */
    private static final float ARROW_CHANCE = 0.2F;
    /** Chance for a harmful witch potion to be dynamite. */
    private static final float DYNAMITE_CHANCE = 0.3F;
    private static final float DYNAMITE_POWER = 2.0F;

    private OrdnanceHandler() {
    }

    private static boolean enabled(ServerLevel level) {
        return CurseData.isActive(level.getServer(), Curses.ORDNANCE);
    }

    // ---------------------------------------------------------------------------------------------------
    // Explosive arrows
    // ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onArrowJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Arrow arrow)) {
            return;
        }
        if (!(arrow.getOwner() instanceof Skeleton skeleton) || !enabled(level)) {
            return;
        }
        if (skeleton.getRandom().nextFloat() >= ARROW_CHANCE) {
            return;
        }

        ExplosiveArrow bolt = new ExplosiveArrow(CursesEntities.EXPLOSIVE_ARROW.get(), level);
        bolt.setOwner(skeleton);
        bolt.moveTo(arrow.getX(), arrow.getY(), arrow.getZ(), arrow.getYRot(), arrow.getXRot());
        bolt.setDeltaMovement(arrow.getDeltaMovement());
        bolt.pickup = AbstractArrow.Pickup.DISALLOWED;

        // Replace the arrow the skeleton just fired. The new entity is not an Arrow, so it is not seen here again.
        event.setCanceled(true);
        level.addFreshEntity(bolt);
        CursesMod.LOGGER.debug("Ordnance: skeleton at {} shot an explosive arrow", skeleton.blockPosition());
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
