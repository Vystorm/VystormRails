package at.esoren.vystormrails;

import static at.esoren.vystormrails.BugFixTest.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

class NetworkMenuTest {
    private void click(boolean permitted, boolean sameWorld, boolean existing) throws Exception {
        SoundRegistryTestSupport.initialize();
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class)); Player player = mock(Player.class);
        World world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.hasPermission("vystormrails.use")).thenReturn(permitted);
        var constructor = NetworkMenu.class.getDeclaredConstructor(RailsPlugin.class, Player.class);
        constructor.setAccessible(true); NetworkMenu menu = constructor.newInstance(plugin, player);
        Network.Station station = new Network.Station("Mine", 32, 64, 0);
        Map<Integer, Network.Station> slots = field(menu, "stationSlots"); slots.put(0, station);
        if (!sameWorld) {
            World other = mock(World.class); when(other.getUID()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(other);
        }
        when(plugin.favoriteStation(player, "Mine")).thenReturn(existing ? station : null);
        Inventory inventory = mock(Inventory.class); when(inventory.getHolder()).thenReturn(menu);
        InventoryView view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player); when(event.getSlot()).thenReturn(0);
        try (var bukkit = mockStatic(Bukkit.class)) { new NetworkMenu.Clicks(plugin).onClick(event); }
        verify(event).setCancelled(true);
        if (permitted && sameWorld && existing) verify(plugin).setDestination(player, "Mine");
        else verify(plugin, never()).setDestination(any(), any());
    }
    @Test void openMapRechecksRevokedPermission() throws Exception { click(false, true, true); }
    @Test void openMapCannotSelectAnOldWorldDestination() throws Exception { click(true, false, true); }
    @Test void openMapRechecksRemovedStation() throws Exception { click(true, true, false); }
    @Test void validMapSelectionStillSetsDestination() throws Exception { click(true, true, true); }
}
