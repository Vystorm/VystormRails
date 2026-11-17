package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Color;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class NetworkTest {
    @Test void findsRailsExactlyAndThroughTiles() {
        Network network = new Network();
        assertTrue(network.add(-5, 13));
        assertFalse(network.add(-5, 13));
        assertTrue(network.has(-5, 13));
        assertTrue(network.any(-6, 12, 2));
        assertFalse(network.any(-3, 12, 2));
        assertTrue(network.any(-8, 8, 8));
        assertTrue(network.any(-64, 0, 64));
        assertFalse(network.any(0, 0, 64));
        assertTrue(network.remove(-5, 13));
        assertFalse(network.any(-64, 0, 64));
        assertEquals(0, network.size());
    }

    @Test void keepsRailsWhenNeighboursInTheSameTileGo() {
        Network network = new Network();
        network.add(1, 1);
        network.add(2, 1);
        network.remove(1, 1);
        assertTrue(network.any(0, 0, 4));
    }

    @Test void stationsByPositionAndArea() {
        Network network = new Network();
        network.station(new Network.Station("Farm", 10, 64, -20));
        network.station(new Network.Station("Farm Hub", 10, 64, -20));
        assertEquals(1, network.stations().size());
        assertEquals("Farm Hub", network.stations(8, -24, 8).getFirst().name());
        assertTrue(network.stations(0, 0, 8).isEmpty());
        assertTrue(network.removeStation(10, 64, -20));
        assertTrue(network.stations().isEmpty());
    }

    @Test void savesAndLoads() throws Exception {
        Network network = new Network();
        network.add(Integer.MIN_VALUE + 3, 7);
        network.add(300_000, -300_000);
        network.station(new Network.Station("Spawn", 0, -60, 0));
        assertTrue(network.dirty());
        byte[] bytes = network.save();
        assertTrue(network.dirty(), "serialization alone does not acknowledge a disk write");
        Network loaded = Network.load(bytes);
        assertFalse(loaded.dirty());
        assertTrue(loaded.has(Integer.MIN_VALUE + 3, 7));
        assertTrue(loaded.has(300_000, -300_000));
        assertTrue(loaded.any(299_968, -300_032, 64));
        assertEquals("Spawn", loaded.stations().iterator().next().name());
        assertEquals(-60, loaded.stations().iterator().next().y());
        assertThrows(java.io.IOException.class, () -> Network.load(new byte[] {1, 2, 3, 4}));
    }

    @Test void nearestNamedStation() {
        Network network = new Network();
        assertNull(network.nearest(0, 0));
        network.station(new Network.Station("", 1, 64, 1));
        network.station(new Network.Station("Farm", 100, 64, 0));
        network.station(new Network.Station("Mine", -30, 64, 40));
        assertEquals("Mine", network.nearest(0, 0).name());
        assertEquals("Farm", network.nearest(80, 0).name());
    }

    @Test void terrainColoursPickTheClosestPane() {
        assertEquals(Material.BLUE_STAINED_GLASS_PANE, NetworkMenu.pane(Color.fromRGB(0x3C44AA)));
        assertEquals(Material.WHITE_STAINED_GLASS_PANE, NetworkMenu.pane(Color.fromRGB(0xFAFAFA)));
        assertEquals(Material.GREEN_STAINED_GLASS_PANE, NetworkMenu.pane(Color.fromRGB(0x5E7C16)));
    }

    @Test
    void unreadableFileIsMovedAsideNotLoadedAsEmpty(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("world.bin");
        java.nio.file.Files.write(file, new byte[] {1, 2, 3, 4});
        java.util.List<String> log = new java.util.ArrayList<>();
        assertNull(Network.read(file, log::add), "no empty network the caller would save over the file");
        assertFalse(java.nio.file.Files.exists(file));
        try (var files = java.nio.file.Files.list(dir)) {
            java.nio.file.Path aside = files.filter(p -> p.getFileName().toString().startsWith("world.bin.broken-")).findFirst().orElseThrow();
            assertArrayEquals(new byte[] {1, 2, 3, 4}, java.nio.file.Files.readAllBytes(aside));
        }
        assertEquals(1, log.size());
        Network good = new Network();
        good.add(1, 2);
        java.nio.file.Files.write(file, good.save());
        assertNotNull(Network.read(file, log::add));
    }
}
