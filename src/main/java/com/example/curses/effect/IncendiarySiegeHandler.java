package com.example.curses.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Shared helpers for mobs that try to break into a player's shelter (Ordnance, Incendiary).
 *
 * <p>"Sealed in" means: a player is close, and the mob has no walking route to them (walls, a closed door,
 * a pillar). Vanilla mobs just stand outside in that case; the curses use this to make them act.</p>
 */
public final class Siege {
    /** Mobs only path about 16 blocks, so a failed path check is only meaningful this close. */
    public static final double PATH_RANGE = 15.0;

    private static final double THROW_SPEED = 1.3;

    private Siege() {
    }

    /**
     * The nearest player within {@link #PATH_RANGE} (at least {@code minDist} away) that this mob cannot walk
     * to, or null. Creative and spectator players never count. A path search is made, so call this sparingly.
     */
    public static Player sealedPlayer(Mob mob, ServerLevel level, double minDist) {
        Player player = level.getNearestPlayer(mob, PATH_RANGE);
        if (player == null || !player.isAlive() || player.isCreative() || player.isSpectator()) {
            return null;
        }
        if (mob.distanceToSqr(player) < minDist * minDist) {
            return null;
        }
        Path path = mob.getNavigation().createPath(player, 0);
        return path == null || !path.canReach() ? player : null;
    }

    /**
     * Lobs a thrown "potion" showing {@code stack} (dynamite, a molotov) so it comes down at {@code aim}.
     * A wall in the way simply makes it burst there, which is the point. Returns false if the spot is out of reach.
     */
    public static boolean lobPotion(Witch witch, ServerLevel level, Vec3 aim, ItemStack stack) {
        ThrownPotion thrown = new ThrownPotion(level, witch);
        thrown.setItem(stack);

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
        level.addFreshEntity(thrown);
        return true;
    }

    /**
     * The launch angle (radians) at which a potion fired at {@link #THROW_SPEED} comes down {@code distance}
     * blocks away and {@code dy} blocks above where it started (the game's own motion rules: move, 1% drag,
     * 0.05 gravity, tried for every whole degree). NaN if nothing lands within about 2 blocks of the spot.
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
