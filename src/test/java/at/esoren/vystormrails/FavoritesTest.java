package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class FavoritesTest {
    @Test void duplicatesIgnoreCaseAndLowerLimitsNeverDeleteEntries() {
        List<String> names = new ArrayList<>();
        assertEquals(Favorites.Result.ADDED, Favorites.add(names, "North Farm", 2));
        assertEquals(Favorites.Result.EXISTS, Favorites.add(names, "NORTH FARM", 2));
        assertEquals(Favorites.Result.ADDED, Favorites.add(names, "车站", 2));
        assertEquals(Favorites.Result.FULL, Favorites.add(names, "Port", 1));
        assertEquals(List.of("North Farm", "车站"), names);
        assertEquals(Favorites.Result.FULL, Favorites.add(new ArrayList<>(), "Farm", 0));
    }

    @Test void persistentDataIsWorldScopedAndReadsAreIndependent() {
        Player player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        Map<NamespacedKey, List<String>> stored = new HashMap<>();
        doAnswer(i -> { stored.put(i.getArgument(0), i.getArgument(2)); return null; })
                .when(data).set(any(), eq(PersistentDataType.LIST.strings()), anyList());
        when(data.getOrDefault(any(), eq(PersistentDataType.LIST.strings()), anyList()))
                .thenAnswer(i -> stored.getOrDefault(i.getArgument(0), i.getArgument(2)));
        doAnswer(i -> { stored.remove(i.getArgument(0)); return null; }).when(data).remove(any());
        UUID world = UUID.randomUUID(), other = UUID.randomUUID();
        List<String> names = new ArrayList<>(List.of("Farm Hub", "车站"));
        Favorites.save(player, world, names);
        names.clear();
        assertEquals(List.of("Farm Hub", "车站"), Favorites.read(player, world));
        Favorites.read(player, world).clear();
        assertEquals(2, Favorites.read(player, world).size());
        assertTrue(Favorites.read(player, other).isEmpty());
        Favorites.save(player, world, List.of());
        assertTrue(stored.isEmpty());
    }

    @Test void resolvesPastRemovedAndRenamedStationsWithoutChoosingPartialMatches() {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class));
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        Network network = new Network();
        Network.Station removed = new Network.Station("Farm", 1, 64, 0);
        Network.Station renamed = new Network.Station("Farm", 2, 64, 0);
        Network.Station valid = new Network.Station("FARM", 30, 64, 0);
        network.station(removed); network.station(renamed); network.station(valid);
        network.station(new Network.Station("Farm Hub", 0, 64, 0));
        when(plugin.network(world)).thenReturn(network);
        when(plugin.stationStillThere(world, removed)).thenReturn(false);
        when(plugin.stationStillThere(world, renamed)).thenAnswer(i -> {
            network.station(new Network.Station("Port", 2, 64, 0)); return true;
        });
        when(plugin.stationStillThere(world, valid)).thenReturn(true);
        when(plugin.favoriteStation(any(), anyString())).thenCallRealMethod();
        assertEquals(valid, plugin.favoriteStation(player, " farm "));
        assertNull(plugin.favoriteStation(player, "Far"));
    }
}
