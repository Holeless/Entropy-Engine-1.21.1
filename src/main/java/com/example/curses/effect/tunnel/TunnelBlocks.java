package com.example.curses.effect.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Everything that touches blocks for the Omniscience tunnels: classifying them, deciding which blocks a mob needs
 * open to walk a step, removing them, and lining the result so it stays open.
 *
 * <p>Blocks are removed with {@code setBlock(..., UPDATE_CLIENTS)} only: no neighbour updates, no drops, no real
 * explosion. That keeps the server cost of a breach to a few block writes, and nothing falls, flows or pops
 * into the tunnel afterwards. The risky neighbours (water, lava, sand, gravel above the ceiling) are replaced
 * with stone while the cell is opened instead.</p>
 */
public final class TunnelBlocks {
    public static final byte OPEN = 0;
    public static final byte FLUID = 1;
    public static final byte SOLID = 2;   // 2..12: solid, rising with hardness
    public static final byte HARD = 20;   // cannot or must not be removed (bedrock, obsidian, chests, unloaded...)

    /** Nothing is placed (floor patches, lining) within this many blocks of the player: placed blocks near you feel like lag. */
    private static final double NO_PLACE_SQR = 8.0 * 8.0;

    private static SoundEvent explodeSound;
    private static boolean soundLooked;

    private TunnelBlocks() {
    }

    public static byte classify(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) {
            return HARD;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return OPEN;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return state.getFluidState().isEmpty() ? OPEN : FLUID;
        }
        if (state.hasBlockEntity()) {
            return HARD; // chests, furnaces, beds, spawners...: never destroyed, the planner routes around them
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || state.getBlock().getExplosionResistance() >= 100.0F) {
            return HARD; // bedrock, obsidian, ancient debris...
        }
        return (byte) (SOLID + Math.min(10, Math.round(hardness)));
    }

    /** The blocks that must be open for a mob (up to an Enderman: 3 tall) to walk from prev to cell. prev may be null. */
    public static List<BlockPos> volume(BlockPos prev, BlockPos cell) {
        List<BlockPos> v = new ArrayList<>(4);
        v.add(cell);
        v.add(cell.above());
        v.add(cell.above(2));
        if (prev != null) {
            int dy = cell.getY() - prev.getY();
            if (dy > 0) {
                v.add(prev.above(3)); // headroom to hop up a step
            } else if (dy < 0) {
                v.add(cell.above(3)); // headroom while stepping off the edge
            }
        }
        return v;
    }

    public static boolean volumeOpen(ServerLevel level, BlockPos prev, BlockPos cell) {
        for (BlockPos p : volume(prev, cell)) {
            if (classify(level, p) != OPEN) {
                return false;
            }
        }
        return true;
    }

    /**
     * Opens the blocks for one step. Returns false (and changes nothing) if something in the way cannot be removed.
     * Lining and floor patches are skipped near the player.
     */
    public static boolean excavate(ServerLevel level, BlockPos prev, BlockPos cell, BlockPos playerPos) {
        List<BlockPos> vol = volume(prev, cell);
        for (BlockPos p : vol) {
            if (classify(level, p) == HARD) {
                return false;
            }
        }
        boolean shown = false;
        for (BlockPos p : vol) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            if (!shown && !state.getCollisionShape(level, p).isEmpty()) {
                level.levelEvent(2001, p, Block.getId(state)); // one break puff + sound per cell
                shown = true;
            }
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (playerPos == null || cell.distSqr(playerPos) >= NO_PLACE_SQR) {
            line(level, vol, cell);
        }
        return true;
    }

    /** Keeps the opened cell open: seals water/lava next to it, props up sand/gravel above it, patches a missing floor. */
    private static void line(ServerLevel level, List<BlockPos> vol, BlockPos cell) {
        for (BlockPos p : vol) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(dir);
                if (vol.contains(n)) {
                    continue;
                }
                BlockState s = stateOrNull(level, n);
                if (s != null && !s.getFluidState().isEmpty()) {
                    fill(level, n);
                }
            }
            BlockPos above = p.above();
            if (!vol.contains(above)) {
                BlockState s = stateOrNull(level, above);
                if (s != null && (!s.getFluidState().isEmpty() || isLooseGravityBlock(s))) {
                    fill(level, above);
                }
            }
        }
        BlockPos floor = cell.below();
        BlockState fs = stateOrNull(level, floor);
        if (fs != null && (fs.getCollisionShape(level, floor).isEmpty() || !fs.getFluidState().isEmpty())) {
            fill(level, floor);
        }
    }

    private static boolean isLooseGravityBlock(BlockState s) {
        return s.getBlock() instanceof FallingBlock && !(s.getBlock() instanceof AnvilBlock) && !s.is(Blocks.DRAGON_EGG);
    }

    private static BlockState stateOrNull(ServerLevel level, BlockPos pos) {
        return level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) ? null : level.getBlockState(pos);
    }

    private static void fill(ServerLevel level, BlockPos pos) {
        Block block = pos.getY() < 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.COBBLESTONE;
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Sound and smoke for a breach. No damage: the breach only removes the tunnel's own blocks. */
    public static void blastEffect(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 4, 1.2, 0.8, 1.2, 0.0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 12, 1.0, 0.6, 1.0, 0.02);
        if (!soundLooked) {
            soundLooked = true;
            explodeSound = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.withDefaultNamespace("entity.generic.explode"));
        }
        if (explodeSound != null) {
            level.playSound(null, at.x, at.y, at.z, explodeSound, SoundSource.HOSTILE, 3.0F, 0.8F + level.getRandom().nextFloat() * 0.3F);
        }
    }
}
