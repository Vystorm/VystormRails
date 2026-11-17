package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

class RouterTest {
    /** Rails by position; loaded where |x| <= loadedTo. */
    private static final class Grid implements Router.Grid {
        final Map<String, Rail.Shape> rails = new HashMap<>();
        int loadedTo = Integer.MAX_VALUE;

        Grid rail(int x, int y, int z, Rail.Shape shape) { rails.put(x + "," + y + "," + z, shape); return this; }

        @Override public boolean loaded(int x, int z) { return Math.abs(x) <= loadedTo; }
        @Override public Rail.Shape rail(int x, int y, int z) { return rails.get(x + "," + y + "," + z); }
        @Override public boolean plain(int x, int y, int z) { return rail(x, y, z) != null; }
    }

    /** Main line east-west on z = 0 from x = -length to length, a branch south from the junction at (0, 0). */
    private static Grid tee(int length) {
        Grid grid = new Grid();
        for (int x = -length; x <= length; x++) grid.rail(x, 64, 0, Rail.Shape.EAST_WEST);
        for (int z = 1; z <= 5; z++) grid.rail(0, 64, z, Rail.Shape.NORTH_SOUTH);
        return grid;
    }

    private static List<Router.Target> at(int x, int y, int z) { return List.of(new Router.Target(x, y, z)); }

    @Test void findsTheBranchToTheStation() {
        Grid grid = tee(5);
        assertTrue(Router.junction(grid, 0, 64, 0));
        assertFalse(Router.junction(grid, 1, 64, 0));
        assertEquals(BlockFace.EAST, Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(5, 64, 0), 1000));
        assertEquals(BlockFace.WEST, Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(-5, 64, 0), 1000));
        assertEquals(BlockFace.SOUTH, Router.exit(grid, 0, 64, 0, BlockFace.EAST, at(0, 64, 5), 1000));
        assertEquals(BlockFace.EAST, Router.exit(grid, 0, 64, 0, BlockFace.EAST, at(5, 64, 0), 1000));
    }

    @Test void parallelTracksAreNoJunction() {
        Grid grid = new Grid();
        for (int x = -3; x <= 3; x++) grid.rail(x, 64, 0, Rail.Shape.EAST_WEST).rail(x, 64, 1, Rail.Shape.EAST_WEST);
        assertFalse(Router.junction(grid, 0, 64, 0));
        assertNull(Router.exit(grid, 0, 64, 0, BlockFace.EAST, at(3, 64, 1), 1000));
    }

    @Test void followsSlopesAndCurves() {
        Grid grid = tee(2);
        grid.rail(3, 64, 0, Rail.Shape.ASCENDING_EAST).rail(4, 65, 0, Rail.Shape.SOUTH_WEST);
        for (int z = 1; z <= 4; z++) grid.rail(4, 65, z, Rail.Shape.NORTH_SOUTH);
        assertEquals(BlockFace.EAST, Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(4, 65, 4), 1000));
    }

    @Test void picksTheShorterOfTwoWays() {
        // A loop: both branches reach the station at (6, 64, 6); west round is longer.
        Grid grid = new Grid();
        grid.rail(0, 64, 0, Rail.Shape.EAST_WEST);
        for (int z = 1; z <= 3; z++) grid.rail(0, 64, z, Rail.Shape.NORTH_SOUTH);
        for (int x = 1; x <= 5; x++) grid.rail(x, 64, 0, Rail.Shape.EAST_WEST);
        grid.rail(6, 64, 0, Rail.Shape.SOUTH_WEST);
        for (int z = 1; z <= 6; z++) grid.rail(6, 64, z, Rail.Shape.NORTH_SOUTH);
        for (int x = -1; x >= -9; x--) grid.rail(x, 64, 0, Rail.Shape.EAST_WEST);
        grid.rail(-10, 64, 0, Rail.Shape.SOUTH_EAST);
        for (int z = 1; z <= 9; z++) grid.rail(-10, 64, z, Rail.Shape.NORTH_SOUTH);
        grid.rail(-10, 64, 10, Rail.Shape.NORTH_EAST);
        for (int x = -9; x <= 5; x++) grid.rail(x, 64, 10, Rail.Shape.EAST_WEST);
        grid.rail(6, 64, 10, Rail.Shape.NORTH_WEST);
        for (int z = 7; z <= 9; z++) grid.rail(6, 64, z, Rail.Shape.NORTH_SOUTH);
        assertEquals(BlockFace.EAST, Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(6, 64, 6), 5000));
    }

    @Test void guessesBeyondTheLoadedTrack() {
        Grid grid = tee(50);
        grid.loadedTo = 3;
        assertEquals(BlockFace.EAST, Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(40, 64, 0), 1000));
        assertEquals(BlockFace.WEST, Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(-40, 64, 2), 1000));
    }

    @Test void givesUpWithoutWayOrTargets() {
        Grid grid = tee(5);
        assertNull(Router.exit(grid, 0, 64, 0, BlockFace.NORTH, at(30, 64, 30), 1000));
        assertNull(Router.exit(grid, 0, 64, 0, BlockFace.NORTH, List.of(), 1000));
        assertNull(Router.exit(grid, 3, 64, 0, BlockFace.EAST, at(5, 64, 0), 1000));
    }

    @Test void shapesConnectTheirEnds() {
        assertTrue(Router.connects(Rail.Shape.NORTH_EAST, BlockFace.EAST));
        assertFalse(Router.connects(Rail.Shape.NORTH_EAST, BlockFace.WEST));
        assertTrue(Router.connects(Rail.Shape.ASCENDING_NORTH, BlockFace.SOUTH));
        assertFalse(Router.connects(Rail.Shape.EAST_WEST, BlockFace.NORTH));
        assertFalse(Router.connects(null, BlockFace.NORTH));
    }
}
