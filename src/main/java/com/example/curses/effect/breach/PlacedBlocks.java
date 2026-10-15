package com.example.curses.effect.breach;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Positions of blocks that players have placed, per dimension, saved with the world ({@code data/curses_placed.dat}).
 * Breach's Endermen use it to tell "placed" blocks (the ones they may move) from the natural world.
 *
 * <p>It is filled while the mod is installed (whether or not Breach is on), capped at {@link #CAP} blocks, and
 * never trusted blindly: a stale entry just points at a block that is gone, which every user of the set checks for (the block must still be there).</p>
 */
public class PlacedBlocks extends SavedData {
    private static final String FILE_NAME = "curses_placed";
    private static final String KEY = "Blocks";
    private static final int CAP = 40_000;

    private final LongOpenHashSet blocks = new LongOpenHashSet();

    public static PlacedBlocks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PlacedBlocks::new, PlacedBlocks::load, DataFixTypes.LEVEL), FILE_NAME);
    }

    private static PlacedBlocks load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedBlocks data = new PlacedBlocks();
        for (long key : tag.getLongArray(KEY)) {
            data.blocks.add(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(KEY, blocks.toLongArray());
        return tag;
    }

    public boolean contains(BlockPos pos) {
        return blocks.contains(pos.asLong());
    }

    public void add(BlockPos pos) {
        if (blocks.size() < CAP && blocks.add(pos.asLong())) {
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (blocks.remove(pos.asLong())) {
            setDirty();
        }
    }
}
