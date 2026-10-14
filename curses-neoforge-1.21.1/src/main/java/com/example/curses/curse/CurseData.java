package com.example.curses.curse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world storage of the active curses. Saved with the overworld as {@code data/curses.dat},
 * so the curses belong to the world (not to a player) and survive restarts.
 */
public class CurseData extends SavedData {
    private static final String FILE_NAME = "curses";
    private static final String KEY_ACTIVE = "Active";
    private static final String KEY_INITIALIZED = "Initialized";

    private final Set<ResourceLocation> active = new LinkedHashSet<>();
    // False until the world has been loaded once with this mod. Used to apply the selection made on
    // the create-world screen exactly once, and never to a pre-existing world.
    private boolean initialized;

    public static CurseData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_NAME);
    }

    private static SavedData.Factory<CurseData> factory() {
        return new SavedData.Factory<>(CurseData::new, CurseData::load, DataFixTypes.LEVEL);
    }

    public static CurseData load(CompoundTag tag, HolderLookup.Provider registries) {
        CurseData data = new CurseData();
        data.initialized = tag.getBoolean(KEY_INITIALIZED);
        ListTag list = tag.getList(KEY_ACTIVE, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null) {
                data.active.add(id);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ResourceLocation id : active) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put(KEY_ACTIVE, list);
        tag.putBoolean(KEY_INITIALIZED, initialized);
        return tag;
    }

    /** Shortcut for "is this curse active in the world this server is running". */
    public static boolean isActive(MinecraftServer server, Curse curse) {
        return get(server).isActive(curse);
    }

    public boolean isActive(Curse curse) {
        return active.contains(curse.id());
    }

    /** Activates or lifts a curse. Returns true if that changed anything. */
    public boolean setActive(Curse curse, boolean on) {
        boolean changed = on ? active.add(curse.id()) : active.remove(curse.id());
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** All active curses that this version of the mod knows about, in selection-screen order. */
    public List<Curse> activeCurses() {
        List<Curse> result = new ArrayList<>();
        for (Curse curse : Curses.all()) {
            if (active.contains(curse.id())) {
                result.add(curse);
            }
        }
        return result;
    }

    public void replaceAll(Collection<ResourceLocation> ids) {
        active.clear();
        active.addAll(ids);
        setDirty();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void markInitialized() {
        initialized = true;
        setDirty();
    }
}
