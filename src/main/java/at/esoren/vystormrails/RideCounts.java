package at.esoren.vystormrails;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Per player the last known name, the blocks ridden in a minecart and the rides (1.22.0, for the rankings
 * {@code rails:distance} and {@code rails:trips}), kept in {@code riders.yml}. A ride counts once the player has ridden
 * {@link #RIDE_BLOCKS} blocks in one go; it ends when he leaves the cart. Counting starts with 1.22.0; older versions do
 * not read the file. A change only marks the store as changed: the plugin writes it at most once a minute off the
 * server thread and once more when it stops. An unreadable file is set aside under another name and never
 * overwritten. Server thread only, except {@link #write(Snapshot)}.
 */
final class RideCounts {
    static final int FORMAT = 1;
    /** Blocks one ride must cover to count as a trip. */
    static final double RIDE_BLOCKS = 16;
    /** A longer step between two moves is a teleport (cart blocks, plugins), not riding. */
    static final double MAX_STEP = 8;

    /** One player: last known name, blocks ridden, rides. */
    static final class Rider {
        String name;
        double distance;
        long trips;

        Rider(String name) { this.name = name; }
    }

    /** What {@link #write} puts on disk, taken on the server thread. */
    record Snapshot(long version, byte[] bytes) {}

    private final Path file;
    private final AtomicFilesInterface files;
    private final Map<UUID, Rider> riders = new LinkedHashMap<>();
    /** Blocks of the ride in progress per player; NaN once the ride has been counted. */
    private final Map<UUID, Double> rides = new HashMap<>();
    private String problem;
    private long version, written;
    private boolean dirty;

    RideCounts(Path file, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        load();
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException broken) {
            Path aside = files.moveAside(file);
            problem = "riders.yml could not be read and was kept as " + (aside == null ? file.getFileName() : aside.getFileName()) + " ("
                    + (broken.getMessage() == null ? broken.getClass().getSimpleName() : broken.getMessage().lines().findFirst().orElse(""))
                    + "); rides are not counted until it is fixed and the server restarts";
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection row = section.getConfigurationSection(key);
            if (row == null) continue;
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException notAPlayer) { continue; }
            Rider rider = new Rider(row.getString("name", "?"));
            double distance = row.getDouble("distance");
            rider.distance = Double.isFinite(distance) ? Math.max(0, distance) : 0;
            rider.trips = Math.max(0, row.getLong("trips"));
            riders.put(id, rider);
        }
    }

    /** The reason the file could not be read at start, null when it was fine or missing. */
    String problem() { return problem; }

    /** A new ride starts (the player got into a cart). */
    void start(UUID player) { if (player != null) rides.put(player, 0.0); }

    /** The ride ends (the player left the cart or the server). */
    void end(UUID player) { if (player != null) rides.remove(player); }

    /**
     * The player's cart moved {@code blocks} blocks. Steps longer than {@link #MAX_STEP} and anything that is not a
     * finite positive number are ignored. True when this step completed a ride.
     */
    boolean moved(UUID player, String name, double blocks) {
        if (player == null || problem != null || !Double.isFinite(blocks) || blocks <= 0 || blocks > MAX_STEP) return false;
        Rider rider = riders.computeIfAbsent(player, id -> new Rider(name));
        if (name != null && !name.isBlank()) rider.name = name;
        rider.distance += blocks;
        boolean counted = false;
        double ride = rides.getOrDefault(player, 0.0);
        if (!Double.isNaN(ride)) {
            ride += blocks;
            if (ride >= RIDE_BLOCKS) { rider.trips++; ride = Double.NaN; counted = true; }
            rides.put(player, ride);
        }
        dirty = true;
        version++;
        return counted;
    }

    double distance(UUID player) {
        Rider rider = riders.get(player);
        return rider == null ? 0 : rider.distance;
    }

    long trips(UUID player) {
        Rider rider = riders.get(player);
        return rider == null ? 0 : rider.trips;
    }

    /** Players by blocks ridden, most first. */
    List<Map.Entry<UUID, Rider>> topDistance(int n) { return top(n, r -> r.distance); }

    /** Players by rides, most first; players without a ride yet are left out. */
    List<Map.Entry<UUID, Rider>> topTrips(int n) { return top(n, r -> r.trips); }

    private List<Map.Entry<UUID, Rider>> top(int n, ToDoubleFunction<Rider> value) {
        List<Map.Entry<UUID, Rider>> rows = new ArrayList<>();
        for (Map.Entry<UUID, Rider> e : riders.entrySet()) if (value.applyAsDouble(e.getValue()) > 0) rows.add(e);
        rows.sort(Comparator.comparingDouble((Map.Entry<UUID, Rider> e) -> value.applyAsDouble(e.getValue())).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    /** The changes to write, or null when nothing changed since the last call (server thread). */
    Snapshot takeChanges() {
        if (!dirty || problem != null) return null;
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Vystorm Rails: blocks ridden and rides of each player, for the rankings rails:distance and rails:trips.",
                "Written by the plugin; stop the server before editing."));
        yaml.set("format", FORMAT);
        yaml.createSection("players");
        for (Map.Entry<UUID, Rider> e : riders.entrySet()) {
            String base = "players." + e.getKey();
            yaml.set(base + ".name", e.getValue().name);
            yaml.set(base + ".distance", Math.round(e.getValue().distance * 10) / 10.0);
            yaml.set(base + ".trips", e.getValue().trips);
        }
        return new Snapshot(version, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a snapshot atomically; an older snapshot than one already written is skipped (any thread). */
    synchronized void write(Snapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.version() < written) return;
        Files.createDirectories(file.getParent());
        files.write(file, snapshot.bytes());
        written = snapshot.version();
    }

    /** After a failed write: the changes are written again next time (server thread). */
    void failed() { dirty = true; }
}
