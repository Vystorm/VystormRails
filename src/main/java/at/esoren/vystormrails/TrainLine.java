package at.esoren.vystormrails;

import java.util.*;

/** Ordered station departures in a repeating UTC epoch-based cycle; never catches up missed departures. */
record TrainLine(String id, UUID owner, UUID world, int periodSeconds, List<Stop> stops) {
    record Stop(String station, int offsetSeconds) {
        Stop { if (station == null || station.isBlank() || station.length() > 48 || station.indexOf('|') >= 0)
            throw new IllegalArgumentException("stop"); station = station.strip(); }
    }
    TrainLine {
        if (id == null || !id.matches("[a-z0-9_-]{1,24}") || owner == null || world == null
                || periodSeconds < 30 || periodSeconds > 86400 || stops == null || stops.size() < 2 || stops.size() > 16)
            throw new IllegalArgumentException("line");
        stops = List.copyOf(stops); int previous = -1; Set<String> names = new HashSet<>();
        for (Stop stop : stops) {
            if (stop.offsetSeconds() <= previous || stop.offsetSeconds() >= periodSeconds
                    || !names.add(stop.station().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("stops");
            previous = stop.offsetSeconds();
        }
    }
    static List<Stop> parseStops(String text) {
        List<Stop> stops = new ArrayList<>();
        for (String value : text.split("\\|", -1)) {
            int at = value.lastIndexOf('@'); if (at < 1) throw new IllegalArgumentException("stop");
            stops.add(new Stop(value.substring(0, at).strip(), Integer.parseInt(value.substring(at + 1).strip())));
        }
        return stops;
    }
    long departure(int index, long earliestMillis) {
        long period = periodSeconds * 1000L, offset = stops.get(index).offsetSeconds() * 1000L;
        long base = Math.multiplyExact(Math.floorDiv(Math.subtractExact(earliestMillis, offset), period), period);
        long due = Math.addExact(base, offset);
        return due < earliestMillis ? Math.addExact(due, period) : due;
    }
    int next(int index) { return (index + 1) % stops.size(); }
    boolean editable(UUID player, boolean admin) { return admin || owner.equals(player); }
}
