package at.esoren.vystormrails;

import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * One station as a chest (1.23.0): its next departures and the day ticket, for players without a Vystorm Screen.
 * Opened by a right-click on a station of the network chest; the ticket button asks for a second click before it
 * buys, as the screen asks for a confirmation.
 */
final class StationMenu implements InventoryHolder {
    static final int SLOT_STATION = 4, SLOT_FIRST_DEPARTURE = 9, MAX_DEPARTURES = 9, SLOT_BACK = 18, SLOT_TICKET = 22;

    private final RailsPlugin plugin;
    private final UUID world;
    private final String station;
    private boolean armed;
    private Inventory inventory;

    private StationMenu(RailsPlugin plugin, Player player, String station) {
        this.plugin = plugin;
        this.world = player.getWorld().getUID();
        this.station = station;
    }

    @Override public Inventory getInventory() { return inventory; }

    static void open(RailsPlugin plugin, Player player, String station) {
        StationMenu menu = new StationMenu(plugin, player, station);
        menu.inventory = plugin.core().compat().platform().createInventory(menu, 27, plugin.lang().component(player, "map-station", "station", station));
        menu.render(player);
        player.openInventory(menu.inventory);
    }

    private void render(Player player) {
        Lang lang = plugin.lang();
        inventory.clear();
        RailsPanel.Board board = plugin.board(player, station);
        List<Component> lore = new ArrayList<>();
        if (board != null && board.tickets())
            lore.add(lang.component(player, "panel-fare-line", "fare", board.fare() == null ? lang.plain(player, "panel-fare-free") : board.fare()));
        inventory.setItem(SLOT_STATION, named(new ItemStack(Material.BELL), lang.component(player, "map-station", "station", station), lore));
        if (board != null && board.departures() != null) {
            if (board.departures().isEmpty())
                inventory.setItem(SLOT_FIRST_DEPARTURE + 4, named(new ItemStack(Material.CLOCK), lang.component(player, "line-board-empty"), List.of()));
            int slot = SLOT_FIRST_DEPARTURE;
            for (TrainLines.Departure d : board.departures()) {
                if (slot >= SLOT_FIRST_DEPARTURE + MAX_DEPARTURES) break;
                inventory.setItem(slot++, named(new ItemStack(Material.CLOCK), lang.component(player, "line-departure", "line", d.line(),
                        "seconds", Long.toString(d.seconds()), "next", d.next()), List.of()));
            }
        }
        inventory.setItem(SLOT_BACK, named(new ItemStack(Material.ARROW), lang.component(player, "panel-back"), List.of()));
        if (board != null && board.tickets()) {
            List<Component> ticket = new ArrayList<>();
            ticket.add(board.ticketHours() > 0 ? lang.component(player, "panel-ticket-valid", "hours", board.ticketHours())
                    : lang.component(player, "panel-ticket-none", "price", board.ticketPrice()));
            ticket.add(lang.component(player, armed ? "panel-buy-ticket-again" : "panel-buy-ticket-confirm", "price", board.ticketPrice()));
            ItemStack item = named(new ItemStack(armed ? Material.EMERALD : Material.PAPER), lang.component(player, "panel-buy-ticket"), ticket);
            inventory.setItem(SLOT_TICKET, item);
        }
    }

    private ItemStack named(ItemStack item, Component name, List<Component> lore) {
        plugin.core().compat().platform().editMeta(item, meta -> {
            plugin.core().compat().platform().displayName(meta, name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            plugin.core().compat().platform().lore(meta, lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        });
        return item;
    }

    /** One click; package-private for tests. True when the chest stays open. */
    boolean click(Player player, int slot) {
        switch (slot) {
            case SLOT_BACK -> {
                NetworkMenu.open(plugin, player);
                return false;
            }
            case SLOT_TICKET -> {
                RailsPanel.Board board = plugin.board(player, station);
                if (board == null || !board.tickets() || !player.hasPermission("vystormrails.ticket")) return true;
                if (!armed) {
                    armed = true;
                } else {
                    armed = false;
                    plugin.ticket(player, true);
                }
            }
            default -> armed = false;
        }
        render(player);
        return true;
    }

    static final class Clicks implements Listener {
        private final RailsPlugin plugin;

        Clicks(RailsPlugin plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof StationMenu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) return;
            if (!player.hasPermission("vystormrails.use") || !menu.world.equals(player.getWorld().getUID())) return;
            int slot = event.getSlot();
            // opening another menu or paying never from inside the click event
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && menu.inventory.getViewers().contains(player)) menu.click(player, slot);
            });
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof StationMenu) event.setCancelled(true);
        }
    }
}
