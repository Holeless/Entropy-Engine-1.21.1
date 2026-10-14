package com.example.curses.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Ordnance: the arrow a skeleton shoots instead of a normal one. It explodes on whatever it hits (block or
 * entity) and is never left behind to pick up. Drawn by {@code ExplosiveArrowRenderer} from the
 * {@code curses:explosive_arrow} item sprite.
 */
public class ExplosiveArrow extends net.minecraft.world.entity.projectile.AbstractArrow {
    /** Explosion radius (TNT is 4.0, a creeper 3.0). */
    public static final float POWER = 1.5F;

    public ExplosiveArrow(EntityType<? extends ExplosiveArrow> type, Level level) {
        super(type, level);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(Items.ARROW);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() && this.getDeltaMovement().lengthSqr() > 0.01) {
            this.level().addParticle(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        detonate();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        detonate();
    }

    private void detonate() {
        if (this.level().isClientSide() || this.isRemoved()) {
            return;
        }
        // MOB interaction: breaks blocks only while mob griefing is on, like a creeper.
        this.level().explode(this, this.getX(), this.getY(), this.getZ(), POWER, Level.ExplosionInteraction.MOB);
        this.discard();
    }
}
