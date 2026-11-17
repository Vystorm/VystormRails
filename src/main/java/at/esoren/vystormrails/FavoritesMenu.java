package at.esoren.vystormrails;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Snapshot of one player's favorites; clicks recheck world, permission and station availability. */
final class FavoritesMenu implements InventoryHolder {
    private final RailsPlugin plugin;
    private final UUID owner, world;
    private List<String> names;
    private Inventory inventory;

    private FavoritesMenu(RailsPlugin plugin, Player player) {
        this.plugin = plugin;
        owner = player.getUniqueId();
        world = player.getWorld().getUID();
    }

    @Override public Inventory getInventory() { return inventory; }

    static void open(RailsPlugin plugin, Player player) {
        FavoritesMenu menu = new FavoritesMenu(plugin, player);
        menu.inventory = plugin.core().compat().platform().createInventory(menu, 54, plugin.lang().component(player, "favorites-title"));
        menu.render(player);
        player.openInventory(menu.inventory);
    }

    private void render(Player player) {
        names = Favorites.read(player, world);
        inventory.clear();
        for (int slot = 0; slot < Math.min(54, names.size()); slot++) {
            String name = names.get(slot);
            var at = player.getLocation();
            Network.Station station = plugin.network(player.getWorld()).nearest(name, at.getBlockX(), at.getBlockZ());
            boolean known = station != null;
            ItemStack item = new ItemStack(known ? Material.BELL : Material.BARRIER);
            plugin.core().compat().platform().editMeta(item, meta -> {
                plugin.core().compat().platform().displayName(meta, plugin.lang().component(player, "map-station", "station", name));
                var lore = new java.util.ArrayList<net.kyori.adventure.text.Component>();
                if (known) lore.add(plugin.lang().component(player, "favorites-location",
                        "distance", Integer.toString(Network.distance(station, at.getBlockX(), at.getBlockZ())),
                        "x", Integer.toString(station.x()), "y", Integer.toString(station.y()), "z", Integer.toString(station.z())));
                lore.add(plugin.lang().component(player, known ? "map-station-click" : "favorites-missing"));
                lore.add(plugin.lang().component(player, "favorites-remove"));
                plugin.core().compat().platform().lore(meta, lore);
            });
            inventory.setItem(slot, item);
        }
        if (names.isEmpty()) {
            ItemStack item = new ItemStack(Material.PAPER);
            plugin.core().compat().platform().editMeta(item, meta -> plugin.core().compat().platform().displayName(meta, plugin.lang().component(player, "favorites-empty")));
            inventory.setItem(22, item);
        }
    }

    void select(Player player, int slot, boolean remove) {
        if (!owner.equals(player.getUniqueId()) || !world.equals(player.getWorld().getUID()) || !player.hasPermission("vystormrails.use")) return;
        if (slot < 0 || slot >= names.size() || slot >= 54) return;
        String name = names.get(slot);
        List<String> saved = Favorites.read(player, world);
        if (!saved.contains(name)) return;
        if (remove) {
            saved.remove(name);
            Favorites.save(player, world, saved);
            plugin.lang().send(player, "favorites-removed", "station", name);
            render(player);
        } else {
            Network.Station station = plugin.favoriteStation(player, name);
            if (station == null) {
                plugin.lang().send(player, "favorites-missing");
                return;
            }
            plugin.setDestination(player, station.name());
        }
    }

    static final class Clicks implements Listener {
        @EventHandler public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof FavoritesMenu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
                menu.select(player, event.getSlot(), event.getClick() == ClickType.RIGHT);
        }

        @EventHandler public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof FavoritesMenu) event.setCancelled(true);
        }
    }
}
