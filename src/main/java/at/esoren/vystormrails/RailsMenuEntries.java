package at.esoren.vystormrails;

import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * "Travel to" in Core's player menu (Core 0.43.0, {@code player-menu}): sneak + right-click a player makes the named
 * station closest to them your destination, auto-routing and switches take your cart there.
 * Server thread.
 */
final class RailsMenuEntries {
    private RailsMenuEntries() {}

    static void register(RailsPlugin plugin) {
        plugin.core().gui().playerMenu().register(plugin, PlayerMenuEntry.builder("travel-to")
                .priority(230)
                .visibleWhen((viewer, target) -> plugin.getConfig().getBoolean("routing.player-menu", true)
                        && viewer.hasPermission("vystormrails.use") && plugin.nearestStation(target) != null)
                .icon((viewer, target) -> {
                    Network.Station station = plugin.nearestStation(target);
                    if (station == null) return null;
                    ItemStack item = plugin.lang().name(new ItemStack(Material.MINECART), viewer, "menu-travel", "player", target.getName());
                    return plugin.lang().lore(item, viewer, "menu-travel-lore", "station", station.name(), "distance", Long.toString(distance(target, station)), "player", target.getName());
                })
                .onClick((viewer, target, click) -> {
                    Network.Station station = plugin.nearestStation(target);
                    if (station != null) plugin.setDestination(viewer, station.name());
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
    }

    private static long distance(Player player, Network.Station station) {
        double dx = station.x() + 0.5 - player.getLocation().getX(), dz = station.z() + 0.5 - player.getLocation().getZ();
        return Math.round(Math.sqrt(dx * dx + dz * dz));
    }
}
