package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class RailSignTest {
    private static RailSign parse(String... lines) { return RailSign.parse("[rails]", lines); }

    @Test void station() {
        RailSign sign = parse("[Rails]", "station", "8", "e Spawn");
        assertEquals(RailSign.Kind.STATION, sign.kind());
        assertEquals(8, sign.seconds());
        assertEquals(BlockFace.EAST, sign.direction());
        assertEquals("Spawn", sign.text());
        RailSign plain = parse("[rails]", "station", "", "Farm Hub");
        assertEquals(5, plain.seconds());
        assertNull(plain.direction());
        assertEquals("Farm Hub", plain.text());
        assertNull(parse("[rails]", "station", "soon", ""));
    }

    @Test void speed() {
        assertEquals(1.0, parse("[rails]", "speed", "72km/h", "").speed(), 1e-9);
        assertEquals(0, parse("[rails]", "speed", "reset", "").speed());
        assertNull(parse("[rails]", "speed", "", ""));
        // Vanilla covers these: powered rails launch, activator rails eject.
        assertNull(parse("[rails]", "launch", "n", "0.8"));
        assertNull(parse("[rails]", "eject"));
    }

    @Test void switchRoutesByDestination() {
        RailSign sign = parse("[rails]", "switch", "Spawn:n, Farm:e", "*:s");
        assertEquals(BlockFace.NORTH, sign.route("spawn"));
        assertEquals(BlockFace.EAST, sign.route("Farm"));
        assertEquals(BlockFace.SOUTH, sign.route("Mine"));
        assertEquals(BlockFace.SOUTH, sign.route(null));
        RailSign noDefault = parse("[rails]", "switch", "Spawn:w", "");
        assertNull(noDefault.route("Mine"));
        assertNull(parse("[rails]", "switch", "nothing", ""));
    }

    @Test void otherActionsAndHeader() {
        assertEquals(RailSign.Kind.COLLECT, parse("[rails]", "collect", "", "").kind());
        assertEquals("Mine", parse("[rails]", "dest", "Mine", "").text());
        assertNull(parse("[rails]", "dest", "", ""));
        assertNull(parse("[other]", "collect"));
        assertNull(parse("[rails]", "fly"));
    }
}
