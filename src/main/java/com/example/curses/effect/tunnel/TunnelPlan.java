package com.example.curses.effect.tunnel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The one shared tunnel leading to a player. Every digger and every follower reads the same list of cells, which
 * is why all creepers widen the same tunnel and all mobs walk the same route.
 *
 * <p>{@code cells} are feet positions from where the tunnel starts to the player. Cells {@code [0, head)} are open
 * and walkable; {@code cells[head]} is the frontier that still has to be dug. Cells are only ever opened, never
 * re-checked behind the head, so nothing the player changes behind it is dug or placed again.</p>
 */
final class TunnelPlan {
    private static final int MAX_PREFIX = 100; // open cells kept behind the head when the plan is rebased

    final UUID playerId;
    final ServerLevel level;
    final List<BlockPos> cells = new ArrayList<>();
    BitSet done = new BitSet();
    int head;
    BlockPos goal;

    long lastActive;
    long nextDig;
    long nextBlast;
    long nextReplan;
    boolean replan;
    boolean dead;
    java.util.UUID primedBy;
    long primedAt;

    TunnelPlan(UUID playerId, ServerLevel level, List<BlockPos> path, BlockPos goal, long now) {
        this.playerId = playerId;
        this.level = level;
        this.cells.addAll(path);
        this.goal = goal;
        this.done.set(0);
        this.head = 1; // the start cell is where the digger stands
        this.lastActive = now;
        this.nextReplan = now + 100;
    }

    boolean frontierPending() {
        return head < cells.size();
    }

    BlockPos lastOpen() {
        return cells.get(Math.max(0, head - 1));
    }

    BlockPos frontier() {
        return head < cells.size() ? cells.get(head) : null;
    }

    /** Moves the head over cells that are open already (caves, tunnels dug earlier, doors opened by someone). */
    void advanceHead() {
        int steps = 0;
        while (head < cells.size() && steps++ < 24) {
            if (done.get(head) || TunnelBlocks.volumeOpen(level, cells.get(head - 1), cells.get(head))) {
                done.set(head);
                head++;
            } else {
                break;
            }
        }
    }

    /** Index of the open cell nearest to pos (within maxSq), or -1. */
    int nearestOpen(Vec3 pos, double maxSq) {
        int best = -1;
        double bestSq = maxSq;
        for (int i = 0; i < head; i++) {
            BlockPos c = cells.get(i);
            double dx = pos.x - (c.getX() + 0.5);
            double dy = pos.y - c.getY();
            double dz = pos.z - (c.getZ() + 0.5);
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = i;
            }
        }
        return best;
    }

    /** Replaces everything after the last open cell with a freshly planned route (newPath starts at that cell). */
    void rebase(List<BlockPos> newPath, BlockPos newGoal) {
        int keepFrom = Math.max(0, head - MAX_PREFIX);
        List<BlockPos> kept = new ArrayList<>(cells.subList(keepFrom, head));
        cells.clear();
        cells.addAll(kept);
        for (int i = 1; i < newPath.size(); i++) {
            cells.add(newPath.get(i));
        }
        done = new BitSet();
        done.set(0, kept.size());
        head = kept.size();
        goal = newGoal;
    }
}
