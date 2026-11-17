package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StationSearchTest {
    private final Network network = new Network();

    private Network.Station station(String name, int x, int y, int z) {
        Network.Station station = new Network.Station(name, x, y, z);
        network.station(station);
        return station;
    }

    @Test void searchesPartialNamesWithSpacesIgnoringCaseAndOuterWhitespace() {
        var farm = station("North Farm Hub", 100, 64, 0);
        station("South Mine", 1, 64, 0);
        assertEquals(List.of(farm), network.search("  FARM h  ", 0, 0));
        assertTrue(network.search("unknown", 0, 0).isEmpty());
    }

    @Test void emptySearchListsOnlyNamedStationsByHorizontalDistance() {
        var far = station("Far", 20, 64, 0);
        var near = station("Near", 3, 200, 4);
        station("  ", 0, 64, 0);
        assertEquals(List.of(near, far), network.search(null, 0, 0));
        assertEquals(List.of(near, far), network.search(" ", 0, 0));
        assertEquals(List.of(far, near), network.search("", 20, 0));
    }

    @Test void tiesAndDuplicateNamesHaveStableOrdering() {
        var beta = station("Beta", 1, 64, 0);
        var upper = station("Alpha", 0, 70, 1);
        var lower = station("Alpha", 0, 60, 1);
        assertEquals(List.of(lower, upper, beta), network.search("", 0, 0));
    }

    @Test void sortsByExactDistanceBeforeRoundingForDisplay() {
        var further = station("A", 10, 64, 1);
        var closer = station("Z", 10, 64, 0);
        assertEquals(List.of(closer, further), network.search("", 0, 0));
    }

    @Test void searchesSavedStationsWithoutChangingTheNetwork() throws Exception {
        station("中央车站", -200, 64, 10);
        Network loaded = Network.load(network.save());
        assertEquals(1, loaded.search("中央", 0, 0).size());
        assertFalse(loaded.dirty());
        assertTrue(new Network().search("", 0, 0).isEmpty());
    }

    @Test void searchDoesNotDependOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            var mine = station("IRON MINE", 0, 64, 0);
            assertEquals(List.of(mine), network.search("iron", 0, 0));
        } finally { Locale.setDefault(previous); }
    }
}
