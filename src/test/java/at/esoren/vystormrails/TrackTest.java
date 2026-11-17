package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

class TrackTest {
    @Test void headingFollowsTheLargerComponent() {
        assertEquals(BlockFace.EAST, Track.heading(0.5, 0.1));
        assertEquals(BlockFace.NORTH, Track.heading(0.1, -0.5));
        assertNull(Track.heading(0, 0));
    }

    @Test void junctionConnectsEntryAndExit() {
        // Heading east means the cart comes from the west side.
        assertEquals(Rail.Shape.EAST_WEST, Track.junction(BlockFace.EAST, BlockFace.EAST));
        assertEquals(Rail.Shape.NORTH_WEST, Track.junction(BlockFace.EAST, BlockFace.NORTH));
        assertEquals(Rail.Shape.SOUTH_WEST, Track.junction(BlockFace.EAST, BlockFace.SOUTH));
        assertEquals(Rail.Shape.SOUTH_EAST, Track.junction(BlockFace.NORTH, BlockFace.EAST));
        assertNull(Track.junction(BlockFace.EAST, BlockFace.WEST));
    }

    @Test void readsSpeedsInBothUnits() {
        assertEquals(0.8, Track.speed("0.8"), 1e-9);
        assertEquals(1.0, Track.speed("72km/h"), 1e-9);
        assertEquals(0.5, Track.speed("36 kmh"), 1e-9);
        assertEquals(1.2, Track.speed("1,2"), 1e-9);
        assertTrue(Double.isNaN(Track.speed("fast")));
        assertTrue(Double.isNaN(Track.speed("-1")));
        assertEquals(72, Track.kmh(1.0));
    }

    @Test void followsCurves() {
        // Heading east into a north-west curve: the cart comes from the west side and leaves north.
        assertEquals(BlockFace.NORTH, Track.onwards(Rail.Shape.NORTH_WEST, BlockFace.EAST));
        assertEquals(BlockFace.WEST, Track.onwards(Rail.Shape.NORTH_WEST, BlockFace.SOUTH));
        assertEquals(BlockFace.EAST, Track.onwards(Rail.Shape.SOUTH_EAST, BlockFace.NORTH));
        assertNull(Track.onwards(Rail.Shape.NORTH_WEST, BlockFace.WEST));
        assertEquals(BlockFace.EAST, Track.onwards(Rail.Shape.EAST_WEST, BlockFace.EAST));
        assertEquals(BlockFace.NORTH, Track.onwards(Rail.Shape.ASCENDING_NORTH, BlockFace.NORTH));
    }

    @Test void classifiesShapes() {
        assertTrue(Track.curve(Rail.Shape.NORTH_EAST));
        assertFalse(Track.curve(Rail.Shape.NORTH_SOUTH));
        assertTrue(Track.slope(Rail.Shape.ASCENDING_EAST));
        assertEquals(BlockFace.EAST, Track.direction("ost"));
        assertNull(Track.direction("back"));
    }
}
