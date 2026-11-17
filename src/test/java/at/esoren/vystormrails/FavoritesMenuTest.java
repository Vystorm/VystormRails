package at.esoren.vystormrails;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import Vystorm.vystorm_core.lang.Lang;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class FavoritesMenuTest {
    @Test void selectionChecksOwnerWorldPermissionAndCurrentStation() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class));
        when(plugin.lang()).thenReturn(mock(Lang.class));
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID id = UUID.randomUUID(), worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.hasPermission("vystormrails.use")).thenReturn(true);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(), eq(PersistentDataType.LIST.strings()), anyList())).thenReturn(List.of("Farm"));
        var constructor = FavoritesMenu.class.getDeclaredConstructor(RailsPlugin.class, Player.class);
        constructor.setAccessible(true);
        FavoritesMenu menu = constructor.newInstance(plugin, player);
        var names = FavoritesMenu.class.getDeclaredField("names");
        names.setAccessible(true); names.set(menu, List.of("Farm"));
        var station = new Network.Station("Farm", 0, 64, 0);
        when(plugin.favoriteStation(player, "Farm")).thenReturn(station);
        menu.select(player, 0, false);
        verify(plugin).setDestination(player, "Farm");
        clearInvocations(plugin);
        menu.select(player, -1, false); menu.select(player, 54, false);
        when(player.hasPermission("vystormrails.use")).thenReturn(false);
        menu.select(player, 0, false);
        when(player.hasPermission("vystormrails.use")).thenReturn(true);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        menu.select(player, 0, false);
        when(world.getUID()).thenReturn(worldId);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        menu.select(player, 0, false);
        verifyNoInteractions(plugin);
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.favoriteStation(player, "Farm")).thenReturn(null);
        menu.select(player, 0, false);
        verify(plugin, never()).setDestination(any(), any());
        verify(plugin.lang()).send(player, "favorites-missing");
        var inventory = FavoritesMenu.class.getDeclaredField("inventory");
        inventory.setAccessible(true); inventory.set(menu, mock(Inventory.class));
        when(plugin.network(world)).thenReturn(new Network());
        when(data.getOrDefault(any(), eq(PersistentDataType.LIST.strings()), anyList()))
                .thenReturn(List.of("Farm"), List.of());
        try (var items = mockConstruction(ItemStack.class)) { menu.select(player, 0, true); }
        verify(data).remove(Favorites.key(worldId));
        verify(plugin.lang()).send(player, "favorites-removed", "station", "Farm");
    }
}
