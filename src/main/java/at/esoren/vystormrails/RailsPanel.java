package at.esoren.vystormrails;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Network and favorites screens. Snapshots render without services; actions always recheck live state. */
final class RailsPanel {
    static final String NETWORK = "network", FAVORITES = "favorites";
    static final int PAGE_SIZE = 9;

    record Station(String key, String name, int x, int y, int z, int distance, boolean known) {}
    record Cell(int slot, int x, int z, Material icon, String station, boolean you) {}
    record Overview(UUID owner, UUID world, boolean favorites, String destination, int stationCount, int rails,
                    List<Station> rows, String selected, int page, int pages, String query, Ui.Sort sort,
                    List<Cell> cells, int scale, String tab) {}

    interface Actions {
        Result change(Player player, Overview snapshot, String operation, Object value);
        void open(Player player, boolean favorites);
    }

    private static final class View {
        final UUID world;
        int x, z, zoom = 2, page;
        String query = "", tab = "stations";
        Ui.Sort sort = new Ui.Sort("distance", false);
        View(Player player) {
            world = player.getWorld().getUID();
            x = player.getLocation().getBlockX();
            z = player.getLocation().getBlockZ();
        }
    }

    private final RailsPlugin plugin;
    private final boolean favorites;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    private final Actions actions = new Actions() {
        @Override public Result change(Player player, Overview snapshot, String operation, Object value) {
            return act(player, snapshot, operation, value);
        }
        @Override public void open(Player player, boolean favorites) {
            if (allowed(player)) {
                if (favorites) plugin.openFavorites(player); else plugin.openNetwork(player);
            }
        }
    };

    RailsPanel(RailsPlugin plugin, boolean favorites) { this.plugin = plugin; this.favorites = favorites; }

    void forget(UUID player) { selected.remove(player); views.remove(player); }
    void clear() { selected.clear(); views.clear(); }

    Panel render(PanelContext ctx) { return panel(ctx, overview(ctx.player()), actions); }

    Overview overview(Player player) {
        UUID id = player.getUniqueId();
        View view = views.get(id);
        if (view == null || !view.world.equals(player.getWorld().getUID())) {
            forget(id);
            view = new View(player);
            views.put(id, view);
        }
        Network network = plugin.network(player.getWorld());
        var at = player.getLocation();
        List<Station> rows = new ArrayList<>();
        if (favorites) {
            for (String name : Favorites.read(player, view.world)) {
                Network.Station station = plugin.favoriteStation(player, name);
                rows.add(station == null ? new Station(key(name), name, 0, 0, 0, 0, false)
                        : row(key(name), station, at.getBlockX(), at.getBlockZ()));
            }
        } else {
            // Work on a copy: validation may prune a vanished station from the network.
            for (Network.Station station : List.copyOf(network.stations())) {
                if (!station.name().isBlank() && plugin.stationStillThere(player.getWorld(), station))
                    rows.add(row("s" + Long.toUnsignedString(station.key()), station, at.getBlockX(), at.getBlockZ()));
            }
        }
        String query = view.query.toLowerCase(Locale.ROOT);
        rows.removeIf(row -> !row.name().toLowerCase(Locale.ROOT).contains(query));
        Comparator<Station> order = view.sort.column().equals("name")
                ? Comparator.comparing(Station::name, String.CASE_INSENSITIVE_ORDER)
                : Comparator.comparingInt(row -> row.known() ? row.distance() : Integer.MAX_VALUE);
        if (view.sort.descending()) order = order.reversed();
        rows.sort(order.thenComparing(Station::key));
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        view.page = Math.clamp(view.page, 0, pages - 1);
        List<Station> page = List.copyOf(rows.subList(view.page * PAGE_SIZE, Math.min(rows.size(), (view.page + 1) * PAGE_SIZE)));
        String chosen = selected.get(id);
        if (page.stream().noneMatch(row -> row.key().equals(chosen))) {
            if (page.isEmpty()) selected.remove(id); else selected.put(id, page.getFirst().key());
        }
        int scale = NetworkMenu.SCALES[view.zoom];
        List<Cell> cells = new ArrayList<>();
        if (!favorites) {
            int originX = Math.floorDiv(view.x, scale) * scale - 4 * scale;
            int originZ = Math.floorDiv(view.z, scale) * scale - 3 * scale;
            for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) {
                int x = originX + c * scale, z = originZ + r * scale;
                List<Network.Station> stations = network.stations(x, z, scale);
                stations.removeIf(station -> !plugin.stationStillThere(player.getWorld(), station));
                boolean you = Math.floorDiv(at.getBlockX() - originX, scale) == c && Math.floorDiv(at.getBlockZ() - originZ, scale) == r;
                String name = stations.isEmpty() ? null : stations.getFirst().name();
                Material icon = you ? Material.PLAYER_HEAD : name != null ? Material.BELL : network.any(x, z, scale)
                        ? Material.RAIL : NetworkMenu.terrain(player.getWorld(), x + scale / 2, z + scale / 2);
                cells.add(new Cell(r * 8 + c, x + scale / 2, z + scale / 2, icon, name, you));
            }
        }
        return new Overview(id, view.world, favorites, plugin.destination(player), network.stations().size(), network.size(),
                page, selected.get(id), view.page, pages, view.query, view.sort, List.copyOf(cells), scale, view.tab);
    }

    private static String key(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static Station row(String key, Network.Station s, int x, int z) {
        return new Station(key, s.name(), s.x(), s.y(), s.z(), Network.distance(s, x, z), true);
    }

    private boolean allowed(Player player) { return plugin.isEnabled() && player.isOnline() && player.hasPermission("vystormrails.use"); }

    Result act(Player player, Overview snapshot, String operation, Object value) {
        if (!allowed(player) || !snapshot.owner().equals(player.getUniqueId()) || !snapshot.world().equals(player.getWorld().getUID()))
            return Result.error(plugin.lang().plain(player, "denied"));
        View view = views.get(player.getUniqueId());
        if (view == null || !view.world.equals(snapshot.world())) return Result.error(plugin.lang().plain(player, "denied"));
        switch (operation) {
            case "select" -> {
                if (snapshot.rows().stream().noneMatch(row -> row.key().equals(value))) return missing(player);
                selected.put(player.getUniqueId(), (String) value);
            }
            case "page" -> view.page = Math.clamp((Integer) value, 0, snapshot.pages() - 1);
            case "sort" -> {
                Ui.Sort sort = (Ui.Sort) value;
                if (!List.of("name", "distance").contains(sort.column())) return missing(player);
                view.sort = sort; view.page = 0;
            }
            case "search" -> { view.query = ((String) value).strip(); view.page = 0; }
            case "tab" -> {
                if (!List.of("stations", "map").contains(value)) return missing(player);
                view.tab = (String) value;
            }
            case "destination", "remove" -> {
                String name = (String) value;
                if (name == null || name.isBlank()) return missing(player);
                if (snapshot.rows().stream().noneMatch(row -> row.name().equals(name))
                        && snapshot.cells().stream().noneMatch(cell -> name.equals(cell.station()))) return missing(player);
                List<String> saved = favorites ? Favorites.read(player, snapshot.world()) : List.of();
                if (favorites && !saved.contains(name)) return missing(player);
                if (operation.equals("remove")) {
                    if (!favorites) return missing(player);
                    saved.remove(name);
                    Favorites.save(player, snapshot.world(), saved);
                    return Result.ok(plugin.lang().plain(player, "favorites-removed", "station", name));
                }
                Network.Station station = plugin.favoriteStation(player, name);
                if (station == null) return missing(player);
                plugin.setDestination(player, station.name());
            }
            case "clear" -> plugin.setDestination(player, null);
            case "zoom" -> {
                int index = Integer.parseInt((String) value);
                if (index < 0 || index >= NetworkMenu.SCALES.length) return missing(player);
                view.zoom = index;
            }
            case "north" -> view.z -= 3 * NetworkMenu.SCALES[view.zoom];
            case "south" -> view.z += 3 * NetworkMenu.SCALES[view.zoom];
            case "west" -> view.x -= 4 * NetworkMenu.SCALES[view.zoom];
            case "east" -> view.x += 4 * NetworkMenu.SCALES[view.zoom];
            case "center" -> { view.x = player.getLocation().getBlockX(); view.z = player.getLocation().getBlockZ(); }
            case "navigate", "refresh" -> { }
            default -> { return missing(player); }
        }
        return Result.ok();
    }

    private Result missing(Player player) { return Result.error(plugin.lang().plain(player, "favorites-missing")); }

    private static Result change(PanelAction a, Overview o, Actions actions, String operation, Object value) {
        Result result = actions.change(a.player(), o, operation, value);
        if (result.success()) a.refresh();
        return result;
    }

    static Panel panel(PanelContext ctx, Overview o, Actions actions) {
        Station chosen = o.rows().stream().filter(row -> row.key().equals(o.selected())).findFirst().orElse(null);
        Ui.Table table = Ui.table("stations").column("name", ctx.text("panel-station")).sortable()
                .column("distance", ctx.text("panel-distance")).sortable().alignEnd()
                .rows(o.rows(), row -> Ui.row(row.key()).cell(row.name()).cell(row.known()
                        ? ctx.text("map-distance", "distance", row.distance()) : ctx.text("panel-unavailable")))
                .empty(ctx.text(o.favorites() && o.query().isEmpty() ? "favorites-empty" : "stations-empty"))
                .selected(o.selected()).sort(o.sort().column(), o.sort().descending())
                .paging(o.page(), o.pages(), (a, page) -> change(a, o, actions, "page", page),
                        (a, sort) -> change(a, o, actions, "sort", sort))
                .onSelect((a, key) -> change(a, o, actions, "select", key)).grow(2);
        Ui.Box detail = Ui.col().gap(6).grow(3).id("station-detail");
        if (chosen != null) {
            detail.children(Ui.heading(chosen.name()).level(2), Ui.item(chosen.known() ? Material.BELL : Material.BARRIER));
            if (chosen.known()) {
                detail.child(Ui.kv().id("station-stats")
                        .row(ctx.text("panel-distance"), ctx.text("map-distance", "distance", chosen.distance()))
                        .row(ctx.text("panel-location"), chosen.x() + ", " + chosen.y() + ", " + chosen.z()));
                if (chosen.name().equalsIgnoreCase(o.destination())) detail.child(Ui.badge(ctx.text("map-station-dest")).color("accent"));
            } else detail.child(Ui.text(ctx.text("favorites-missing")));
            detail.child(Ui.button("destination").label(ctx.text("panel-destination")).icon(Ui.item(Material.MINECART)).primary()
                    .disabled(!chosen.known()).onClick(a -> change(a, o, actions, "destination", chosen.name())));
            if (o.favorites()) detail.child(Ui.button("remove").label(ctx.text("panel-remove")).icon(Ui.item(Material.BARRIER)).danger()
                    .confirm(ctx.text("panel-remove"), ctx.text("panel-remove-confirm", "station", chosen.name()))
                    .onClick(a -> change(a, o, actions, "remove", chosen.name())));
        } else detail.child(Ui.text(ctx.text("panel-pick")));
        Ui.Box stations = Ui.col().gap(6).children(Ui.input("search", ctx.text("panel-search")).value(o.query()).maxLength(100)
                        .search((a, query) -> change(a, o, actions, "search", query)), Ui.row().gap(8).children(table, detail));
        Panel panel = Panel.page(ctx.text(o.favorites() ? "favorites-title" : "map-title"))
                .subtitle(ctx.text("map-stations", "count", o.stationCount(), "rails", o.rails()))
                .icon(Ui.item(o.favorites() ? Material.BELL : Material.MINECART)).size(Panel.Size.LARGE);
        if (o.favorites()) panel.child(stations);
        else panel.child(Ui.tabs("views").tab("stations", ctx.text("panel-stations"), stations)
                .tab("map", ctx.text("panel-map"), map(ctx, o, actions)).select(o.tab())
                .onTab((a, tab) -> change(a, o, actions, "tab", tab)));
        panel.child(Ui.text(o.destination() == null ? ctx.text("dest-none") : ctx.text("map-you-dest", "dest", o.destination())).id("current-destination"));
        return panel.footer(Ui.button("other-menu").label(ctx.text(o.favorites() ? "map-title" : "favorites-title"))
                        .icon(Ui.item(o.favorites() ? Material.MINECART : Material.BELL)).ghost().onClick(a -> {
                            Result result = actions.change(a.player(), o, "navigate", null);
                            if (result.success()) a.closeAndThen(player -> {
                                if (actions.change(player, o, "navigate", null).success()) actions.open(player, !o.favorites());
                            });
                            return result;
                        }),
                Ui.button("refresh").label(ctx.text("panel-refresh")).icon(Ui.item(Material.COMPASS)).ghost()
                        .onClick(a -> change(a, o, actions, "refresh", null)),
                Ui.button("clear").label(ctx.text("panel-clear")).icon(Ui.item(Material.BARRIER)).disabled(o.destination() == null)
                        .onClick(a -> change(a, o, actions, "clear", null)));
    }

    private static Ui.Box map(PanelContext ctx, Overview o, Actions actions) {
        Ui.Grid grid = Ui.grid(8).gap(2).id("network-map");
        for (Cell cell : o.cells()) {
            String label = cell.you() ? ctx.text("map-you") : cell.station() != null
                    ? cell.station().isBlank() ? ctx.text("station-unnamed") : cell.station()
                    : cell.icon() == Material.RAIL ? ctx.text("map-rail") : ctx.text("map-coords", "x", cell.x(), "z", cell.z());
            Ui.Button button = Ui.button("cell-" + cell.slot()).label(label).icon(Ui.item(cell.icon())).ghost()
                    .tooltip(ctx.text("map-coords", "x", cell.x(), "z", cell.z()))
                    .disabled(cell.station() == null || cell.station().isBlank())
                    .onClick(a -> change(a, o, actions, "destination", cell.station()));
            grid.child(button);
        }
        Ui.Select zoom = Ui.select("zoom", ctx.text("panel-zoom"));
        for (int i = 0; i < NetworkMenu.SCALES.length; i++) {
            zoom.option(Integer.toString(i), ctx.text("map-zoom", "scale", NetworkMenu.SCALES[i]));
            if (NetworkMenu.SCALES[i] == o.scale()) zoom.value(Integer.toString(i));
        }
        zoom.onChange((a, value) -> change(a, o, actions, "zoom", value));
        Ui.Box controls = Ui.row().gap(4).wrap();
        for (String direction : List.of("north", "south", "west", "east", "center"))
            controls.child(Ui.button(direction).label(ctx.text("map-" + direction)).icon(Ui.item(direction.equals("center") ? Material.COMPASS : Material.ARROW))
                    .onClick(a -> change(a, o, actions, direction, null)));
        return Ui.col().gap(6).children(zoom, grid, controls);
    }
}
