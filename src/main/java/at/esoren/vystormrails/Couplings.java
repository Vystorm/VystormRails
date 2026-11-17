package at.esoren.vystormrails;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which carts are coupled into trains. Every cart has at most two neighbours and a link never closes a loop, so a train
 * is always a line of carts from one end to the other. Pure bookkeeping, no Bukkit.
 */
final class Couplings {
    /** Why two carts cannot be coupled. */
    enum Refusal { SAME, ALREADY, FULL, LOOP, TOO_LONG }

    private final Map<UUID, List<UUID>> links = new HashMap<>();

    /** Null when a and b may be coupled into a train of at most {@code maxLength} carts. */
    Refusal check(UUID a, UUID b, int maxLength) {
        if (a.equals(b)) return Refusal.SAME;
        if (neighbours(a).contains(b)) return Refusal.ALREADY;
        if (neighbours(a).size() >= 2 || neighbours(b).size() >= 2) return Refusal.FULL;
        List<UUID> train = train(a);
        if (train.contains(b)) return Refusal.LOOP;
        if (train.size() + train(b).size() > maxLength) return Refusal.TOO_LONG;
        return null;
    }

    /** Links a and b (no checks; use {@link #check} first). */
    void link(UUID a, UUID b) {
        if (a.equals(b)) return;
        List<UUID> first = links.computeIfAbsent(a, id -> new ArrayList<>(2)), second = links.computeIfAbsent(b, id -> new ArrayList<>(2));
        if (!first.contains(b)) first.add(b);
        if (!second.contains(a)) second.add(a);
    }

    boolean unlink(UUID a, UUID b) {
        boolean removed = remove(a, b);
        remove(b, a);
        return removed;
    }

    private boolean remove(UUID from, UUID other) {
        List<UUID> list = links.get(from);
        if (list == null || !list.remove(other)) return false;
        if (list.isEmpty()) links.remove(from);
        return true;
    }

    /** Uncouples the cart from both neighbours; returns them. */
    List<UUID> unlinkAll(UUID cart) {
        List<UUID> partners = new ArrayList<>(neighbours(cart));
        for (UUID partner : partners) unlink(cart, partner);
        return partners;
    }

    List<UUID> neighbours(UUID cart) { return links.getOrDefault(cart, List.of()); }

    boolean coupled(UUID cart) { return links.containsKey(cart); }

    int carts() { return links.size(); }

    /** The whole train of this cart in order from one end to the other; just the cart when it is not coupled. */
    List<UUID> train(UUID cart) {
        UUID end = cart, previous = null;
        Set<UUID> seen = new HashSet<>();
        while (seen.add(end)) {
            UUID next = null;
            for (UUID neighbour : neighbours(end)) if (!neighbour.equals(previous)) { next = neighbour; break; }
            if (next == null) break;
            previous = end;
            end = next;
        }
        List<UUID> train = new ArrayList<>();
        Set<UUID> added = new HashSet<>();
        UUID at = end;
        previous = null;
        while (at != null && added.add(at)) {
            train.add(at);
            UUID next = null;
            for (UUID neighbour : neighbours(at)) if (!neighbour.equals(previous)) { next = neighbour; break; }
            previous = at;
            at = next;
        }
        return train;
    }

    /** Every train with at least two carts, each once. */
    List<List<UUID>> trains() {
        List<List<UUID>> trains = new ArrayList<>();
        Set<UUID> done = new HashSet<>();
        for (UUID cart : links.keySet()) {
            if (done.contains(cart)) continue;
            List<UUID> train = train(cart);
            done.addAll(train);
            if (train.size() > 1) trains.add(train);
        }
        return trains;
    }

    boolean sameTrain(UUID a, UUID b) { return coupled(a) && coupled(b) && train(a).contains(b); }


    // ---- self-healing: partners that were removed while the other cart was not loaded --------------------------------

    /** How many removed carts are remembered so partners that load later drop them at once. */
    static final int GONE_REMEMBERED = 4096;
    private final Set<UUID> gone = new LinkedHashSet<>();
    private final Map<UUID, Integer> missing = new HashMap<>();

    /**
     * A cart was destroyed or removed for good: uncouples it and remembers it, so a partner whose chunk was not loaded
     * drops the link when it loads. Returns the partners it was uncoupled from.
     */
    List<UUID> removed(UUID cart) {
        missing.remove(cart);
        gone.add(cart);
        if (gone.size() > GONE_REMEMBERED) { var oldest = gone.iterator(); oldest.next(); oldest.remove(); }
        return unlinkAll(cart);
    }

    boolean gone(UUID cart) { return gone.contains(cart); }

    /**
     * Links stored on a cart that just loaded. Partners known to be gone, and partners that cannot take another link,
     * are left out. Returns true when the stored form no longer matches and should be written again.
     */
    boolean restore(UUID cart, List<UUID> stored) {
        boolean stale = false;
        for (UUID partner : stored) {
            if (partner.equals(cart) || gone.contains(partner)) { stale = true; continue; }
            if (neighbours(cart).contains(partner)) continue;
            if (neighbours(cart).size() < 2 && neighbours(partner).size() < 2 && !sameTrain(cart, partner)) link(cart, partner);
            else stale = true;
        }
        return stale;
    }

    /**
     * One tick in which a coupled cart was not found. {@code checkable}: every chunk it could be in is loaded with its
     * entities. Once it was missing for {@code grace} checkable ticks in a row it is gone: it is uncoupled and the
     * partners it was uncoupled from are returned (empty while waiting). A tick that is not checkable starts over.
     */
    List<UUID> missing(UUID cart, boolean checkable, int grace) {
        if (!checkable) { missing.remove(cart); return List.of(); }
        if (missing.merge(cart, 1, Integer::sum) < grace) return List.of();
        missing.remove(cart);
        return unlinkAll(cart);
    }

    /** The cart was found this tick. */
    void present(UUID cart) { missing.remove(cart); }

    /** Tells whether a chunk is loaded with its entities. */
    interface ChunkCheck { boolean loaded(int chunkX, int chunkZ); }

    /** Whether every chunk within {@code radius} blocks (square) of x/z is loaded: a cart last seen there must be found. */
    static boolean areaLoaded(double x, double z, double radius, ChunkCheck check) {
        int minX = (int) Math.floor(x - radius) >> 4, maxX = (int) Math.floor(x + radius) >> 4;
        int minZ = (int) Math.floor(z - radius) >> 4, maxZ = (int) Math.floor(z + radius) >> 4;
        for (int cx = minX; cx <= maxX; cx++)
            for (int cz = minZ; cz <= maxZ; cz++) if (!check.loaded(cx, cz)) return false;
        return true;
    }

    void clear() { links.clear(); missing.clear(); gone.clear(); }

    /** Stored form on the cart: the neighbours' UUIDs separated by commas. */
    static String format(List<UUID> neighbours) {
        StringBuilder text = new StringBuilder();
        for (UUID id : neighbours) text.append(text.isEmpty() ? "" : ",").append(id);
        return text.toString();
    }

    static List<UUID> parse(String text) {
        List<UUID> ids = new ArrayList<>(2);
        if (text == null || text.isBlank()) return ids;
        for (String part : text.split(",")) {
            try {
                UUID id = UUID.fromString(part.strip());
                if (!ids.contains(id) && ids.size() < 2) ids.add(id);
            } catch (IllegalArgumentException broken) { }
        }
        return ids;
    }

    /**
     * Speed along the track for a cart {@code gap} blocks behind the cart it follows when the train runs at
     * {@code speed}: a little faster while the gap is too wide, slower (or back) while it is too narrow.
     */
    static double follow(double gap, double spacing, double speed, double cap) {
        double error = gap - spacing;
        if (Math.abs(error) < 0.05) error = 0;
        return Math.clamp(speed + error * 0.5, -0.2, cap);
    }
}
