package at.esoren.vystormrails;

import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The rail network as a chest menu: 8×6 map cells (north up) and a control column. Every cell stands for a square of
 * blocks; it shows you, a station, a rail or the terrain as coloured glass. Clicking a station makes it your destination.
 * Works without a resource pack.
 */
final class NetworkMenu implements InventoryHolder {
    static final int[] SCALES = {2, 4, 8, 16, 32, 64};
    private static final int COLUMNS = 8, ROWS = 6;
    private static final int ZOOM = 8, NORTH = 17, SOUTH = 26, WEST = 35, EAST = 44, CENTER = 53;
    private static final Map<Material, Material> PANES = new EnumMap<>(Material.class);

    private final RailsPlugin plugin;
    private final UUID world;
    private final Map<Integer, Network.Station> stationSlots = new HashMap<>();
    private int centerX, centerZ, zoom = 2;
    private Inventory inventory;

    private NetworkMenu(RailsPlugin plugin, Player player) {
        this.plugin = plugin;
        this.world = player.getWorld().getUID();
        centerX = player.getLocation().getBlockX();
        centerZ = player.getLocation().getBlockZ();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    static void open(RailsPlugin plugin, Player player) {
        NetworkMenu menu = new NetworkMenu(plugin, player);
        menu.inventory = plugin.core().compat().platform().createInventory(menu, COLUMNS * ROWS + ROWS, plugin.lang().component(player, "map-title"));
        menu.render(player);
        player.openInventory(menu.inventory);
    }

    int scale() { return SCALES[zoom]; }

    private void render(Player player) {
        Lang lang = plugin.lang();
        World world = Bukkit.getWorld(this.world);
        if (world == null) return;
        Network network = plugin.network(world);
        int scale = scale();
        int originX = Math.floorDiv(centerX, scale) * scale - COLUMNS / 2 * scale;
        int originZ = Math.floorDiv(centerZ, scale) * scale - ROWS / 2 * scale;
        int youColumn = Math.floorDiv(player.getLocation().getBlockX() - originX, scale);
        int youRow = Math.floorDiv(player.getLocation().getBlockZ() - originZ, scale);
        boolean here = player.getWorld().getUID().equals(this.world);
        String destination = plugin.destination(player);
        stationSlots.clear();
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int slot = row * 9 + column, x = originX + column * scale, z = originZ + row * scale;
                ItemStack item;
                List<Network.Station> stations = network.stations(x, z, scale);
                stations.removeIf(station -> !plugin.stationStillThere(world, station));
                if (here && column == youColumn && row == youRow) {
                    item = you(lang, player, destination);
                } else if (!stations.isEmpty()) {
                    Network.Station station = stations.getFirst();
                    stationSlots.put(slot, station);
                    item = station(lang, player, station, destination, here);
                } else if (network.any(x, z, scale)) {
                    item = named(new ItemStack(Material.RAIL), lang.component(player, "map-rail"), List.of(lang.component(player, "map-coords", "x", Integer.toString(x + scale / 2), "z", Integer.toString(z + scale / 2))));
                } else {
                    item = new ItemStack(terrain(world, x + scale / 2, z + scale / 2));
                    plugin.core().compat().platform().editMeta(item, meta -> meta.setHideTooltip(true));
                }
                inventory.setItem(slot, item);
            }
        }
        inventory.setItem(ZOOM, named(new ItemStack(Material.SPYGLASS), lang.component(player, "map-zoom", "scale", Integer.toString(scale)), List.of(lang.component(player, "map-zoom-lore"))));
        inventory.setItem(NORTH, named(new ItemStack(Material.ARROW), lang.component(player, "map-north"), List.of()));
        inventory.setItem(SOUTH, named(new ItemStack(Material.ARROW), lang.component(player, "map-south"), List.of()));
        inventory.setItem(WEST, named(new ItemStack(Material.ARROW), lang.component(player, "map-west"), List.of()));
        inventory.setItem(EAST, named(new ItemStack(Material.ARROW), lang.component(player, "map-east"), List.of()));
        List<Component> centerLore = new ArrayList<>();
        centerLore.add(lang.component(player, "map-stations", "count", Integer.toString(network.stations().size()), "rails", Integer.toString(network.size())));
        if (destination != null) centerLore.add(lang.component(player, "map-center-clear", "dest", destination));
        inventory.setItem(CENTER, named(new ItemStack(Material.COMPASS), lang.component(player, "map-center"), centerLore));
    }

    private ItemStack you(Lang lang, Player player, String destination) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        plugin.core().compat().platform().editMeta(item, SkullMeta.class, meta -> meta.setOwningPlayer(player));
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(player, "map-coords", "x", Integer.toString(player.getLocation().getBlockX()), "z", Integer.toString(player.getLocation().getBlockZ())));
        if (destination != null) lore.add(lang.component(player, "map-you-dest", "dest", destination));
        return named(item, lang.component(player, "map-you"), lore);
    }

    private ItemStack station(Lang lang, Player player, Network.Station station, String destination, boolean here) {
        boolean target = station.name().equalsIgnoreCase(destination);
        String name = station.name().isBlank() ? lang.plain(player, "station-unnamed") : station.name();
        List<Component> lore = new ArrayList<>();
        if (here) {
            double dx = station.x() + 0.5 - player.getLocation().getX(), dz = station.z() + 0.5 - player.getLocation().getZ();
            lore.add(lang.component(player, "map-distance", "distance", Long.toString(Math.round(Math.sqrt(dx * dx + dz * dz)))));
        }
        if (!station.name().isBlank()) lore.add(lang.component(player, target ? "map-station-dest" : "map-station-click"));
        if (!station.name().isBlank() && plugin.board(player, station.name()) != null) lore.add(lang.component(player, "map-station-right-click"));
        ItemStack item = named(new ItemStack(Material.BELL), lang.component(player, "map-station", "station", name), lore);
        if (target) plugin.core().compat().platform().editMeta(item, meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    private ItemStack named(ItemStack item, Component name, List<Component> lore) {
        plugin.core().compat().platform().editMeta(item, meta -> {
            plugin.core().compat().platform().displayName(meta, name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            plugin.core().compat().platform().lore(meta, lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        });
        return item;
    }

    /** Glass pane in the colour closest to the map colour of the surface; grey where the chunk is not loaded. */
    static Material terrain(World world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return Material.GRAY_STAINED_GLASS_PANE;
        Block top = world.getHighestBlockAt(x, z);
        return PANES.computeIfAbsent(top.getType(), type -> pane(top.getBlockData().getMapColor()));
    }

    static Material pane(Color color) {
        DyeColor best = DyeColor.GRAY;
        int bestDistance = Integer.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            Color c = dye.getColor();
            int dr = c.getRed() - color.getRed(), dg = c.getGreen() - color.getGreen(), db = c.getBlue() - color.getBlue();
            int distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (distance < bestDistance) { bestDistance = distance; best = dye; }
        }
        return Material.valueOf(best.name() + "_STAINED_GLASS_PANE");
    }

    static final class Clicks implements Listener {
        private final RailsPlugin plugin;

        Clicks(RailsPlugin plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof NetworkMenu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) return;
            if (!player.hasPermission("vystormrails.use") || !menu.world.equals(player.getWorld().getUID())) return;
            int slot = event.getSlot(), scale = menu.scale();
            switch (slot) {
                case ZOOM -> menu.zoom = Math.clamp(menu.zoom + (event.isRightClick() ? 1 : -1), 0, SCALES.length - 1);
                case NORTH -> menu.centerZ -= ROWS / 2 * scale;
                case SOUTH -> menu.centerZ += ROWS / 2 * scale;
                case WEST -> menu.centerX -= COLUMNS / 2 * scale;
                case EAST -> menu.centerX += COLUMNS / 2 * scale;
                case CENTER -> {
                    if (event.isRightClick()) plugin.setDestination(player, null);
                    menu.centerX = player.getLocation().getBlockX();
                    menu.centerZ = player.getLocation().getBlockZ();
                }
                default -> {
                    Network.Station station = menu.stationSlots.get(slot);
                    if (station == null || station.name().isBlank()) return;
                    Network.Station current = plugin.favoriteStation(player, station.name());
                    if (current == null) return;
                    if (event.isRightClick() && plugin.board(player, current.name()) != null) {
                        // 1.23.0: departures and day ticket of this station
                        plugin.getServer().getScheduler().runTask(plugin, () -> { if (player.isOnline()) StationMenu.open(plugin, player, current.name()); });
                        return;
                    }
                    plugin.setDestination(player, current.name());
                }
            }
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
            menu.render(player);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof NetworkMenu) event.setCancelled(true);
        }
    }
}
