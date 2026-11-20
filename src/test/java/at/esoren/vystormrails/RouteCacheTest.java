package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

/** A cached route is forgotten when a station appears, is renamed or removed, not only when rails change. */
class RouteCacheTest {
    private final UUID world = UUID.randomUUID(), other = UUID.randomUUID();

    @Test
    void aNewStationMakesTheJunctionLookAgain() {
        Network network = new Network();
        RouteCache cache = new RouteCache(10_000);
        cache.put(world, network.revision(), "junction:NORTH:mine", null); // no station "Mine" yet: no way
        assertEquals(Optional.empty(), cache.get(world, network.revision(), "junction:NORTH:mine"));
        network.station(new Network.Station("Mine", 40, 64, 0));
        assertNull(cache.get(world, network.revision(), "junction:NORTH:mine"), "the old answer must not be reused");
        cache.put(world, network.revision(), "junction:NORTH:mine", BlockFace.EAST);
        assertEquals(Optional.of(BlockFace.EAST), cache.get(world, network.revision(), "junction:NORTH:mine"));
        network.removeStation(40, 64, 0);
        assertNull(cache.get(world, network.revision(), "junction:NORTH:mine"));
    }

    @Test
    void worldsAreSeparateAndTheCacheStaysBounded() {
        RouteCache cache = new RouteCache(3);
        cache.put(world, 1, "a", BlockFace.WEST);
        assertNull(cache.get(other, 1, "a"));
        for (int i = 0; i < 10; i++) cache.put(world, 1, "k" + i, BlockFace.SOUTH);
        assertTrue(cache.size() <= 3);
    }
}
