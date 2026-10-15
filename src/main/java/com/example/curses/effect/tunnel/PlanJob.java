package com.example.curses.effect.tunnel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A time-sliced weighted A* that finds the cheapest tunnel from a start cell to the player.
 *
 * <p>Moves are one block sideways, level or one up/down (a stair a mob can walk). Open air costs nothing, rock
 * costs more the harder it is, water and lava cost a lot, missing floor costs a patch, and chests, bedrock,
 * obsidian and unloaded chunks cannot be entered at all. The search is run in slices ({@link #step}) against a
 * nanosecond deadline so it never costs more than a fraction of a tick, and it only reads loaded chunks.
 * If it gives up (node cap), the closest node it reached becomes a partial plan, which is then continued.</p>
 */
final class PlanJob {
    private static final int MAX_EXPANSIONS = 9000;
    private static final double WEIGHT = 1.25;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[] DYS = {0, 1, -1};
    private static final int MARGIN_H = 16;
    private static final int MARGIN_V = 20;

    private static final class Node {
        final long pos;
        final int h;
        final double g;
        final double f;
        final Node parent;
        boolean closed;

        Node(long pos, int h, double g, Node parent) {
            this.pos = pos;
            this.h = h;
            this.g = g;
            this.f = g + WEIGHT * h;
            this.parent = parent;
        }
    }

    final ServerLevel level;
    final UUID playerId;
    final BlockPos start;
    final BlockPos goal;
    final boolean rebase;

    /** Set once finished: the cells from start to the end of the route, or null if no route was found. */
    List<BlockPos> result;

    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
        int c = Double.compare(a.f, b.f);
        return c != 0 ? c : Integer.compare(a.h, b.h);
    });
    private final Node startNode;
    private Node best;
    private int expanded;

    PlanJob(ServerLevel level, UUID playerId, BlockPos start, BlockPos goal, boolean rebase) {
        this.level = level;
        this.playerId = playerId;
        this.start = start.immutable();
        this.goal = goal.immutable();
        this.rebase = rebase;
        this.minX = Math.min(start.getX(), goal.getX()) - MARGIN_H;
        this.maxX = Math.max(start.getX(), goal.getX()) + MARGIN_H;
        this.minZ = Math.min(start.getZ(), goal.getZ()) - MARGIN_H;
        this.maxZ = Math.max(start.getZ(), goal.getZ()) + MARGIN_H;
        this.minY = Math.max(level.getMinBuildHeight() + 1, Math.min(start.getY(), goal.getY()) - MARGIN_V);
        this.maxY = Math.min(level.getMaxBuildHeight() - 6, Math.max(start.getY(), goal.getY()) + MARGIN_V);

        this.startNode = new Node(start.asLong(), heuristic(start.getX(), start.getY(), start.getZ()), 0.0, null);
        this.nodes.put(startNode.pos, startNode);
        this.open.add(startNode);
        this.best = startNode;
    }

    /** Runs until the deadline. Returns true once finished (see {@link #result}). */
    boolean step(long deadlineNanos) {
        int sinceClock = 0;
        while (!open.isEmpty()) {
            if (++sinceClock >= 16) {
                sinceClock = 0;
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
            Node n = open.poll();
            if (n.closed) {
                continue;
            }
            n.closed = true;
            expanded++;
            if (n.h == 0) {
                complete(n);
                return true;
            }
            if (n.h < best.h || (n.h == best.h && n.g < best.g)) {
                best = n;
            }
            if (expanded >= MAX_EXPANSIONS) {
                break;
            }
            expand(n);
        }
        complete(best); // no complete route: continue from the closest point reached
        return true;
    }

    private void complete(Node end) {
        if (end == startNode || end.h >= startNode.h) {
            result = null;
            return;
        }
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            path.add(BlockPos.of(n.pos));
        }
        Collections.reverse(path);
        result = path.size() >= 2 ? path : null;
    }

    private void expand(Node n) {
        int x = BlockPos.getX(n.pos);
        int y = BlockPos.getY(n.pos);
        int z = BlockPos.getZ(n.pos);
        for (int[] d : DIRS) {
            for (int dy : DYS) {
                int nx = x + d[0];
                int ny = y + dy;
                int nz = z + d[1];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) {
                    continue;
                }
                long key = BlockPos.asLong(nx, ny, nz);
                Node existing = nodes.get(key);
                if (existing != null && existing.closed) {
                    continue;
                }
                double cost = moveCost(x, y, z, nx, ny, nz);
                if (cost == INF) {
                    continue;
                }
                double g = n.g + cost;
                if (existing != null && g >= existing.g) {
                    continue;
                }
                if (existing != null) {
                    existing.closed = true; // superseded: skipped when it comes off the queue
                }
                Node next = new Node(key, heuristic(nx, ny, nz), g, n);
                nodes.put(key, next);
                open.add(next);
            }
        }
    }

    /** 0 inside the goal area (within 2 blocks of the player), otherwise a lower bound on the steps still needed. */
    private int heuristic(int x, int y, int z) {
        int manhattan = Math.abs(x - goal.getX()) + Math.abs(z - goal.getZ());
        int vertical = Math.abs(y - goal.getY());
        return Math.max(Math.max(0, manhattan - 2), Math.max(0, vertical - 2));
    }

    private double moveCost(int fx, int fy, int fz, int gx, int gy, int gz) {
        double cost = 1.0 + (gy != fy ? 0.4 : 0.0);
        double c;
        // Same blocks as TunnelBlocks.volume(prev, cell)
        if ((c = blockCost(gx, gy, gz)) == INF) {
            return INF;
        }
        cost += c;
        if ((c = blockCost(gx, gy + 1, gz)) == INF) {
            return INF;
        }
        cost += c;
        if ((c = blockCost(gx, gy + 2, gz)) == INF) {
            return INF;
        }
        cost += c;
        if (gy > fy) {
            if ((c = blockCost(fx, fy + 3, fz)) == INF) {
                return INF;
            }
            cost += c;
        } else if (gy < fy) {
            if ((c = blockCost(gx, gy + 3, gz)) == INF) {
                return INF;
            }
            cost += c;
        }
        byte floor = cls(gx, gy - 1, gz);
        if (floor == TunnelBlocks.OPEN || floor == TunnelBlocks.FLUID) {
            cost += 2.5; // a floor has to be patched in
        }
        return cost;
    }

    private double blockCost(int x, int y, int z) {
        byte c = cls(x, y, z);
        if (c == TunnelBlocks.HARD) {
            return INF;
        }
        if (c == TunnelBlocks.OPEN) {
            return 0.0;
        }
        if (c == TunnelBlocks.FLUID) {
            return 6.0;
        }
        return 0.6 + (c - TunnelBlocks.SOLID) * 0.35;
    }

    private byte cls(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        byte c = TunnelBlocks.classify(level, BlockPos.of(key));
        cache.put(key, c);
        return c;
    }
}
