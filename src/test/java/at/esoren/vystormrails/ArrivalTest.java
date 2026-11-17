package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ArrivalTest {
    @Test void arrivesOnlyAtTheNamedStation() {
        assertTrue(RailsPlugin.arrived("Farm", "farm"));
        assertTrue(RailsPlugin.arrived(" Farm Hub", "farm hub "));
        assertFalse(RailsPlugin.arrived("Farm", "Farm Hub"));
        assertFalse(RailsPlugin.arrived("", ""));
        assertFalse(RailsPlugin.arrived("Farm", null));
        assertFalse(RailsPlugin.arrived(null, "Farm"));
    }

    @Test void nearestStationOfThatNameAndItsDistance() {
        Network network = new Network();
        network.station(new Network.Station("Farm", 100, 64, 0));
        network.station(new Network.Station("farm", -30, 70, 40));
        network.station(new Network.Station("Mine", 1, 64, 1));
        Network.Station farm = network.nearest("FARM", 0, 0);
        assertEquals(-30, farm.x());
        assertEquals(50, Network.distance(farm, 0, 0));
        assertEquals(100, network.nearest("Farm", 90, 0).x());
        assertNull(network.nearest("Harbour", 0, 0));
        assertNull(network.nearest(" ", 0, 0));
    }
}
