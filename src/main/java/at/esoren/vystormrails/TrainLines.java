package at.esoren.vystormrails;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/** Timetables use the existing routing and station holds; they never spawn carts or load tracks. */
final class TrainLines implements Listener {
    private static final NamespacedKey LINE = new NamespacedKey("vystormrails", "line");
    private static final NamespacedKey INDEX = new NamespacedKey("vystormrails", "line_index");
    private static final NamespacedKey UNTIL = new NamespacedKey("vystormrails", "line_until");
    private static final NamespacedKey DIRECTION = new NamespacedKey("vystormrails", "line_direction");
    private static final NamespacedKey SPEED = new NamespacedKey("vystormrails", "line_speed");
    private final RailsPlugin plugin;
    private final TrainLineStore store;
    TrainLines(RailsPlugin plugin) throws Exception {
        this.plugin = plugin; store = new TrainLineStore(plugin.getDataFolder().toPath().resolve("lines.yml"), plugin.core().data().files());
    }
    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (World world : plugin.getServer().getWorlds())
            for (Minecart cart : world.getEntitiesByClass(Minecart.class)) restore(cart);
    }
    @EventHandler public void loaded(EntitiesLoadEvent event) {
        List<Minecart> carts = event.getEntities().stream().filter(Minecart.class::isInstance).map(Minecart.class::cast).toList();
        plugin.getServer().getScheduler().runTask(plugin, () -> carts.stream().filter(Entity::isValid).forEach(this::restore));
    }
    void restore(Minecart cart) {
        if (!bound(cart)) return;
        if (!valid(cart)) { fail(cart); return; }
        var data = cart.getPersistentDataContainer(); Long due = data.get(UNTIL, PersistentDataType.LONG);
        if (due == null) return;
        try {
            BlockFace direction = BlockFace.valueOf(data.get(DIRECTION, PersistentDataType.STRING));
            double speed = data.getOrDefault(SPEED, PersistentDataType.DOUBLE, 0.4);
            if (!Track.horizontal(direction) || !Double.isFinite(speed) || speed <= 0 || due < 0)
                throw new IllegalArgumentException("Invalid saved departure");
            plugin.lineHold(cart, due, direction, speed);
        } catch (RuntimeException invalid) { fail(cart); }
    }
    boolean bound(Minecart cart) { return cart.getPersistentDataContainer().has(LINE, PersistentDataType.STRING); }
    private TrainLine line(Minecart cart) {
        String id = cart.getPersistentDataContainer().get(LINE, PersistentDataType.STRING);
        return id == null ? null : store.get(cart.getWorld().getUID(), id);
    }
    private boolean stationsExist(TrainLine line, World world) {
        Network network = plugin.network(world);
        for (TrainLine.Stop stop : line.stops()) {
            boolean found = false;
            for (Network.Station station : network.search(stop.station(), 0, 0)) {
                if (station.name().equalsIgnoreCase(stop.station()) && plugin.stationStillThere(world, station)
                        && network.stations().contains(station)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }
    private boolean valid(Minecart cart) {
        TrainLine line = line(cart); int index = cart.getPersistentDataContainer().getOrDefault(INDEX, PersistentDataType.INTEGER, -1);
        return line != null && index >= 0 && index < line.stops().size() && stationsExist(line, cart.getWorld());
    }
    /** -1 ordinary stop, 0 failed line, otherwise the durable departure deadline. */
    long arrive(Minecart cart, String station, long minimumDeparture, BlockFace direction, double speed) {
        if (!bound(cart)) return -1;
        if (!valid(cart)) { fail(cart); return 0; }
        TrainLine line = line(cart); var data = cart.getPersistentDataContainer();
        int index = data.getOrDefault(INDEX, PersistentDataType.INTEGER, 0);
        if (!line.stops().get(index).station().equalsIgnoreCase(station)) return -1;
        if (direction == null) { fail(cart); return 0; }
        long due = line.departure(index, minimumDeparture);
        data.set(INDEX, PersistentDataType.INTEGER, line.next(index)); data.set(UNTIL, PersistentDataType.LONG, due);
        data.set(DIRECTION, PersistentDataType.STRING, direction.name()); data.set(SPEED, PersistentDataType.DOUBLE, speed);
        plugin.cartDestination(cart, line.stops().get(line.next(index)).station());
        return due;
    }
    boolean depart(Minecart cart) {
        if (!bound(cart)) return true;
        if (!valid(cart)) { fail(cart); return false; }
        var data = cart.getPersistentDataContainer(); data.remove(UNTIL); data.remove(DIRECTION); data.remove(SPEED); return true;
    }
    private void fail(Minecart cart) {
        clear(cart);
        for (Entity rider : cart.getPassengers()) if (rider instanceof Player player) plugin.lang().send(player, "line-failed");
    }
    private void clear(Minecart cart) {
        var data = cart.getPersistentDataContainer();
        for (NamespacedKey key : List.of(LINE, INDEX, UNTIL, DIRECTION, SPEED)) data.remove(key);
        plugin.cartDestination(cart, null); plugin.cancelLineHold(cart); cart.setVelocity(new Vector());
    }
    /** Tab completion for the words after {@code /rails line}: the action first, then the id of an existing line. */
    static List<String> suggest(int wordIndex, String firstWord, Collection<String> lineIds) {
        if (wordIndex == 0) return List.of("list", "show", "assign", "stop", "delete", "create");
        if (wordIndex == 1 && firstWord != null) {
            String action = firstWord.toLowerCase(Locale.ROOT);
            if (action.equals("show") || action.equals("assign") || action.equals("delete")) return List.copyOf(lineIds);
        }
        return List.of();
    }
    List<String> lineIds(World world) { return store.all(world.getUID()).stream().map(TrainLine::id).toList(); }
    void command(Player player, String raw) {
        String[] words = raw == null ? new String[0] : raw.strip().split("\\s+", 4);
        if (words.length == 0 || words[0].equalsIgnoreCase("list")) {
            for (TrainLine line : store.all(player.getWorld().getUID())) preview(player, line); return;
        }
        String action = words[0].toLowerCase(Locale.ROOT), id = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
        try {
            if (action.equals("create") && words.length == 4) {
                if (!player.hasPermission("vystormrails.train")) { plugin.lang().send(player, "denied"); return; }
                if (store.get(player.getWorld().getUID(), id) != null) { plugin.lang().send(player, "line-exists"); return; }
                TrainLine line = new TrainLine(id, player.getUniqueId(), player.getWorld().getUID(), Integer.parseInt(words[2]), TrainLine.parseStops(words[3]));
                if (!stationsExist(line, player.getWorld())) { plugin.lang().send(player, "line-missing"); return; }
                store.put(line); plugin.lang().send(player, "line-saved", "line", id); preview(player, line); return;
            }
            if (action.equals("stop")) {
                Minecart cart = ownedCart(player); if (cart == null) return;
                clear(cart); plugin.lang().send(player, "line-stopped"); return;
            }
            TrainLine line = store.get(player.getWorld().getUID(), id);
            if (line == null) { plugin.lang().send(player, "line-missing"); return; }
            if (action.equals("show")) { preview(player, line); return; }
            if (action.equals("delete")) {
                if (!line.editable(player.getUniqueId(), player.hasPermission("vystormrails.admin"))) { plugin.lang().send(player, "denied"); return; }
                store.remove(line);
                for (Minecart cart : player.getWorld().getEntitiesByClass(Minecart.class))
                    if (id.equals(cart.getPersistentDataContainer().get(LINE, PersistentDataType.STRING))) fail(cart);
                plugin.lang().send(player, "line-deleted", "line", id); return;
            }
            if (action.equals("assign")) {
                Minecart cart = ownedCart(player); if (cart == null) return;
                if (!stationsExist(line, player.getWorld())) { plugin.lang().send(player, "line-missing"); return; }
                clear(cart); var data = cart.getPersistentDataContainer();
                data.set(LINE, PersistentDataType.STRING, id); data.set(INDEX, PersistentDataType.INTEGER, 0);
                plugin.cartDestination(cart, line.stops().getFirst().station());
                plugin.lang().send(player, "line-assigned", "line", id); return;
            }
        } catch (IllegalArgumentException invalid) { plugin.lang().send(player, "line-usage"); return; }
        catch (java.io.IOException failure) { plugin.getLogger().warning("Cannot save train timetable: " + failure); plugin.lang().send(player, "line-save-failed"); return; }
        plugin.lang().send(player, "line-usage");
    }
    private Minecart ownedCart(Player player) {
        Minecart cart = plugin.cartOf(player);
        if (cart == null) { plugin.lang().send(player, "no-cart"); return null; }
        if (!player.hasPermission("vystormrails.train") || !plugin.lineAssignable(cart, player)) {
            plugin.lang().send(player, "line-cart-denied"); return null;
        }
        return cart;
    }
    private void preview(Player player, TrainLine line) {
        plugin.lang().send(player, "line-preview", "line", line.id(), "period", Integer.toString(line.periodSeconds()),
                "stops", String.join(" → ", line.stops().stream().map(stop -> stop.station() + " @+" + stop.offsetSeconds() + "s").toList()));
    }
    void departures(Player player, String stationName) {
        Network.Station station = stationName == null || stationName.isBlank() ? plugin.nearestStation(player) : plugin.favoriteStation(player, stationName);
        if (station == null) { plugin.lang().send(player, "line-missing"); return; }
        plugin.lang().send(player, "line-board", "station", station.name());
        long now = System.currentTimeMillis(); int shown = 0;
        for (TrainLine line : store.all(player.getWorld().getUID())) for (int index = 0; index < line.stops().size(); index++) {
            if (!line.stops().get(index).station().equalsIgnoreCase(station.name())) continue;
            plugin.lang().send(player, "line-departure", "line", line.id(), "seconds", Long.toString((line.departure(index, now) - now + 999) / 1000),
                    "next", line.stops().get(line.next(index)).station()); shown++;
        }
        if (shown == 0) plugin.lang().send(player, "line-board-empty");
    }
}
