package at.esoren.vystormrails;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rails of one world as seen from above, learned from placed rails and from carts driving over them, plus the
 * stations found on {@code [rails] station} signs. A coarse index of 4×4 tiles keeps zoomed-out map lookups cheap.
 */
final class Network {
    record Station(String name, int x, int y, int z) {
        long key() { return pack(x, y, z); }
    }

    static final int TILE = 4;
    private static final int MAGIC = 0x56525431; // "VRT1"

    private final Set<Long> rails = new HashSet<>();
    private final Map<Long, Integer> tiles = new HashMap<>();
    private final Map<Long, Station> stations = new HashMap<>();
    private long revision;
    private volatile long savedRevision;

    boolean add(int x, int z) {
        if (!rails.add(column(x, z))) return false;
        tiles.merge(column(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)), 1, Integer::sum);
        revision++;
        return true;
    }

    boolean remove(int x, int z) {
        if (!rails.remove(column(x, z))) return false;
        tiles.computeIfPresent(column(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)), (key, count) -> count > 1 ? count - 1 : null);
        revision++;
        return true;
    }

    boolean has(int x, int z) { return rails.contains(column(x, z)); }

    int size() { return rails.size(); }

    /** Any rail in the square from (x, z) with this edge length; tile-aligned squares use the coarse index. */
    boolean any(int x, int z, int size) {
        if (size >= TILE && Math.floorMod(x, TILE) == 0 && Math.floorMod(z, TILE) == 0 && size % TILE == 0) {
            for (int tx = x / TILE, ex = tx + size / TILE; tx < ex; tx++)
                for (int tz = Math.floorDiv(z, TILE), ez = tz + size / TILE; tz < ez; tz++)
                    if (tiles.containsKey(column(tx, tz))) return true;
            return false;
        }
        for (int dx = 0; dx < size; dx++)
            for (int dz = 0; dz < size; dz++)
                if (has(x + dx, z + dz)) return true;
        return false;
    }

    void station(Station station) {
        if (!station.equals(stations.put(station.key(), station))) revision++;
    }

    boolean removeStation(int x, int y, int z) {
        boolean removed = stations.remove(pack(x, y, z)) != null;
        if (removed) revision++;
        return removed;
    }

    Collection<Station> stations() { return stations.values(); }

    /** The named station closest to (x, z), or null when there is none. */
    Station nearest(int x, int z) {
        Station best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Station station : stations.values()) {
            if (station.name().isBlank()) continue;
            long dx = station.x() - (long) x, dz = station.z() - (long) z, distance = dx * dx + dz * dz;
            if (distance < bestDistance) { bestDistance = distance; best = station; }
        }
        return best;
    }

    /** The station with this name (ignoring case) closest to (x, z), or null when the network has none of that name. */
    Station nearest(String name, int x, int z) {
        if (name == null || name.isBlank()) return null;
        Station best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Station station : stations.values()) {
            if (!station.name().equalsIgnoreCase(name.strip())) continue;
            long dx = station.x() - (long) x, dz = station.z() - (long) z, distance = dx * dx + dz * dz;
            if (distance < bestDistance) { bestDistance = distance; best = station; }
        }
        return best;
    }

    /** Whole blocks in a straight line (from above) from (x, z) to the station. */
    static int distance(Station station, int x, int z) {
        long dx = station.x() - (long) x, dz = station.z() - (long) z;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt((double) (dx * dx + dz * dz))));
    }

    /** Named stations matching a substring, nearest first; ties have a stable name/coordinate order. */
    List<Station> search(String query, int x, int z) {
        String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        return stations.values().stream()
                .filter(station -> !station.name().isBlank() && station.name().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .sorted(java.util.Comparator.comparingDouble((Station station) -> {
                    double dx = (double) station.x() - x, dz = (double) station.z() - z;
                    return dx * dx + dz * dz;
                }).thenComparing(Station::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Station::name)
                        .thenComparingInt(Station::x).thenComparingInt(Station::y).thenComparingInt(Station::z))
                .toList();
    }

    /** Stations in the square from (x, z) with this edge length. */
    List<Station> stations(int x, int z, int size) {
        List<Station> found = new ArrayList<>(1);
        for (Station station : stations.values())
            if (station.x() >= x && station.x() < x + size && station.z() >= z && station.z() < z + size) found.add(station);
        return found;
    }

    boolean dirty() { return revision != savedRevision; }

    long revision() { return revision; }

    /** Serialize writes per world, acknowledge only successful snapshots and discard late older writes. */
    synchronized void write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files, java.nio.file.Path file, long version, byte[] bytes) throws IOException {
        if (version <= savedRevision) return;
        java.nio.file.Files.createDirectories(file.getParent());
        files.write(file, bytes);
        savedRevision = version;
    }

    byte[] save() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + rails.size() * 8);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(rails.size());
            for (long rail : rails) out.writeLong(rail);
            out.writeInt(stations.size());
            for (Station station : stations.values()) {
                out.writeUTF(station.name());
                out.writeInt(station.x());
                out.writeInt(station.y());
                out.writeInt(station.z());
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    static Network load(byte[] data) throws IOException {
        Network network = new Network();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("not a Vystorm Rails network file");
            int rails = in.readInt();
            if (rails < 0 || rails > (in.available() - 4) / 8) throw new IOException("Invalid rail count");
            for (int i = rails; i > 0; i--) {
                long rail = in.readLong();
                network.add((int) (rail >> 32), (int) rail);
            }
            int stations = in.readInt();
            if (stations < 0 || stations > in.available() / 14) throw new IOException("Invalid station count");
            for (int i = stations; i > 0; i--) network.station(new Station(in.readUTF(), in.readInt(), in.readInt(), in.readInt()));
            if (in.available() != 0) throw new IOException("Unexpected trailing network data");
        }
        network.savedRevision = network.revision;
        return network;
    }

    /**
     * Reads a saved network file. An unreadable one is moved aside as {@code <name>.broken-<date-time>} (never
     * overwritten), {@code severe} gets a console message and null is returned; the caller must not save that world.
     */
    static Network read(java.nio.file.Path file, java.util.function.Consumer<String> severe) {
        try { return load(java.nio.file.Files.readAllBytes(file)); }
        catch (IOException | RuntimeException broken) {
            String aside = file.getFileName() + ".broken-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            try { java.nio.file.Files.move(file, file.resolveSibling(aside)); } catch (IOException moveFailed) { aside = file.getFileName().toString(); }
            severe.accept("Network map " + file.getFileName() + " could not be read (" + broken + "). It was kept as network/" + aside
                    + " and this world's map is not saved until the next restart. Fix or remove that file before restarting.");
            return null;
        }
    }

    static long column(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    static long pack(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
}
