package at.esoren.vystormrails;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.BlockFace;

/**
 * Auto-routing results per world, junction, heading and destination. A result is only valid for the network revision
 * it was found in: a station sign placed, renamed or removed, or a rail placed or broken, changes the revision and
 * forgets the world's old results, so a cart never keeps following a way to a station that moved or was not there yet.
 * Server thread.
 */
final class RouteCache {
    private record Entry(long revision, Optional<BlockFace> exit) {}

    private final Map<String, Entry> routes = new HashMap<>();
    private final int max;

    RouteCache(int max) { this.max = max; }

    /** The cached exit (empty = no way) or null when nothing valid is cached for this revision of the world's network. */
    Optional<BlockFace> get(UUID world, long revision, String key) {
        Entry entry = routes.get(world + ":" + key);
        return entry == null || entry.revision() != revision ? null : entry.exit();
    }

    void put(UUID world, long revision, String key, BlockFace exit) {
        if (routes.size() >= max) routes.clear();
        routes.put(world + ":" + key, new Entry(revision, Optional.ofNullable(exit)));
    }

    void clear() { routes.clear(); }

    int size() { return routes.size(); }
}
