package at.esoren.vystormrails;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

/**
 * Auto-routing: which way a cart with a destination leaves a junction. A junction is a plain rail with three or four
 * rails pointing at it (a T or a crossing); every other rail leads where its shape says. The search follows the real
 * track as far as it is loaded (A*, step cost 1); where the track runs into an unloaded chunk the straight distance to
 * the station is the estimate for the rest of the way. Pure, so it can be tested without a server.
 */
final class Router {
    static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** The world as the router sees it. */
    interface Grid {
        boolean loaded(int x, int z);

        /** Shape of the rail in this block, {@code null} when there is none. */
        Rail.Shape rail(int x, int y, int z);

        /** True for a plain rail (the only kind that can curve, so the only kind that can be a junction). */
        boolean plain(int x, int y, int z);
    }

    record Target(int x, int y, int z) {}

    private record Node(int x, int y, int z, BlockFace heading, int cost, int estimate, BlockFace exit) {}

    private record Visit(int x, int y, int z, BlockFace heading) {}

    private Router() {}

    /**
     * Exit a cart heading {@code heading} should take at the junction (x, y, z) to reach one of the targets; {@code null}
     * when there is no junction, no way or the search gave up after {@code limit} steps.
     */
    static BlockFace exit(Grid grid, int x, int y, int z, BlockFace heading, Collection<Target> targets, int limit) {
        if (targets.isEmpty() || !Track.horizontal(heading) || !junction(grid, x, y, z)) return null;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> a.cost() + a.estimate() != b.cost() + b.estimate()
                ? Integer.compare(a.cost() + a.estimate(), b.cost() + b.estimate()) : Integer.compare(b.cost(), a.cost()));
        Set<Visit> visited = new HashSet<>();
        for (BlockFace exit : exits(grid, x, y, z, heading)) step(grid, x, y, z, exit, 0, exit, targets, open);
        int steps = 0;
        while (!open.isEmpty() && steps++ < limit) {
            Node node = open.poll();
            if (node.heading() == null) return node.exit(); // ran into unloaded track: the best guess beyond
            if (reached(node, targets)) return node.exit();
            if (!visited.add(new Visit(node.x(), node.y(), node.z(), node.heading()))) continue;
            for (BlockFace exit : exits(grid, node.x(), node.y(), node.z(), node.heading()))
                step(grid, node.x(), node.y(), node.z(), exit, node.cost(), node.exit(), targets, open);
        }
        return null;
    }

    /** Ways out of this rail for a cart moving {@code heading}: at a junction every connected side but back. */
    private static List<BlockFace> exits(Grid grid, int x, int y, int z, BlockFace heading) {
        if (junction(grid, x, y, z)) {
            List<BlockFace> exits = new java.util.ArrayList<>(3);
            for (BlockFace side : SIDES) if (side != heading.getOppositeFace() && pointsHere(grid, x, y, z, side)) exits.add(side);
            return exits;
        }
        Rail.Shape shape = grid.rail(x, y, z);
        if (shape == null || !connects(shape, heading.getOppositeFace())) return List.of();
        BlockFace onwards = Track.onwards(shape, heading);
        return onwards == null ? List.of() : List.of(onwards);
    }

    private static void step(Grid grid, int x, int y, int z, BlockFace exit, int cost, BlockFace first, Collection<Target> targets, PriorityQueue<Node> open) {
        int nx = x + exit.getModX(), nz = z + exit.getModZ();
        if (!grid.loaded(nx, nz)) {
            open.add(new Node(nx, y, nz, null, cost + 1, estimate(nx, nz, targets), first));
            return;
        }
        int[] next = next(grid, nx, y, nz);
        if (next == null) return;
        int ny = next[0];
        if (!junction(grid, nx, ny, nz) && !connects(grid.rail(nx, ny, nz), exit.getOppositeFace())) return;
        open.add(new Node(nx, ny, nz, exit, cost + 1, estimate(nx, nz, targets), first));
    }

    /** The y of the rail at (x, z) on this level or one up or down; {@code null} when there is none. */
    private static int[] next(Grid grid, int x, int y, int z) {
        for (int dy : new int[] {0, 1, -1}) if (grid.rail(x, y + dy, z) != null) return new int[] {y + dy};
        return null;
    }

    /** A plain rail with at least three neighbouring rails that lead to it. */
    static boolean junction(Grid grid, int x, int y, int z) {
        if (!grid.plain(x, y, z)) return false;
        int count = 0;
        for (BlockFace side : SIDES) if (pointsHere(grid, x, y, z, side)) count++;
        return count >= 3;
    }

    /** True when the rail beside (x, y, z) on this side leads to it (a plain rail beside counts as well). */
    private static boolean pointsHere(Grid grid, int x, int y, int z, BlockFace side) {
        int nx = x + side.getModX(), nz = z + side.getModZ();
        if (!grid.loaded(nx, nz)) return false;
        int[] next = next(grid, nx, y, nz);
        if (next == null) return false;
        Rail.Shape shape = grid.rail(nx, next[0], nz);
        return connects(shape, side.getOppositeFace());
    }

    /** True when a rail of this shape has an end on this side. */
    static boolean connects(Rail.Shape shape, BlockFace side) {
        if (shape == null) return false;
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> side == BlockFace.NORTH || side == BlockFace.SOUTH;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> side == BlockFace.EAST || side == BlockFace.WEST;
            default -> shape.name().contains(side.name());
        };
    }

    private static boolean reached(Node node, Collection<Target> targets) {
        for (Target target : targets) if (target.x() == node.x() && target.z() == node.z() && Math.abs(target.y() - node.y()) <= 1) return true;
        return false;
    }

    /** Fewest rails to the nearest target: rails only go north, east, south and west. */
    private static int estimate(int x, int z, Collection<Target> targets) {
        int best = Integer.MAX_VALUE;
        for (Target target : targets) best = Math.min(best, Math.abs(target.x() - x) + Math.abs(target.z() - z));
        return best;
    }
}
