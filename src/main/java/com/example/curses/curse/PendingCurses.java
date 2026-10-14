package com.example.curses.curse;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * The curses picked on the create-world screen that have not been applied to a world yet.
 *
 * <p>The client fills this in; when the new world's server starts it moves the selection into the
 * world's {@link CurseData} and clears it. Plain Java on purpose (no client classes), so it is safe
 * to reference from common code on a dedicated server.</p>
 */
public final class PendingCurses {
    private static final Set<ResourceLocation> SELECTED = new LinkedHashSet<>();

    private PendingCurses() {
    }

    public static synchronized boolean isSelected(Curse curse) {
        return SELECTED.contains(curse.id());
    }

    public static synchronized void set(Curse curse, boolean selected) {
        if (selected) {
            SELECTED.add(curse.id());
        } else {
            SELECTED.remove(curse.id());
        }
    }

    public static synchronized int count() {
        return SELECTED.size();
    }

    public static synchronized void clear() {
        SELECTED.clear();
    }

    /** Returns the current selection and clears it. */
    public static synchronized Set<ResourceLocation> consume() {
        Set<ResourceLocation> copy = new LinkedHashSet<>(SELECTED);
        SELECTED.clear();
        return copy;
    }
}
