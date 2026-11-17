package at.esoren.vystormrails;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.block.BlockFace;

/**
 * A sign below or beside the track. Line 1 is the header ({@code [rails]}), line 2 the action, lines 3–4 its values:
 * <ul>
 *   <li>{@code station} – line 3 seconds to wait (default 5), line 4 departure direction (n/e/s/w, empty = onwards) and name</li>
 *   <li>{@code speed} – line 3 top speed ({@code 0.8} or {@code 60km/h}); {@code reset} restores the default</li>
 *   <li>{@code dest} – line 3 destination name set on the cart</li>
 *   <li>{@code switch} – lines 3–4 rules {@code Name:direction}, {@code *:direction} for every other cart</li>
 *   <li>{@code collect} – empty carts are picked up (back to their owner)</li>
 * </ul>
 * Launching and ejecting stay with vanilla powered and activator rails.
 */
public record RailSign(Kind kind, double speed, int seconds, BlockFace direction, String text, Map<String, BlockFace> routes) {
    public enum Kind { STATION, SPEED, DEST, SWITCH, COLLECT, FREIGHT }

    /** Parses the four lines; {@code null} when line 1 is not the header or the action is unknown or incomplete. */
    public static RailSign parse(String header, String[] lines) {
        if (lines == null || lines.length < 2 || !header.equalsIgnoreCase(clean(lines[0]))) return null;
        String action = clean(lines[1]).toLowerCase(Locale.ROOT);
        String third = lines.length > 2 ? clean(lines[2]) : "", fourth = lines.length > 3 ? clean(lines[3]) : "";
        return switch (action) {
            case "station", "halt" -> {
                int seconds = 5;
                if (!third.isEmpty()) {
                    try { seconds = Integer.parseInt(third.replaceAll("(?i)s$", "")); } catch (NumberFormatException bad) { yield null; }
                }
                String[] parts = fourth.split("\\s+", 2);
                BlockFace direction = Track.direction(parts[0]);
                String name = direction == null ? fourth : parts.length > 1 ? parts[1] : "";
                yield new RailSign(Kind.STATION, Double.NaN, Math.clamp(seconds, 0, 600), direction, name, Map.of());
            }
            case "speed" -> {
                if (third.equalsIgnoreCase("reset") || third.equalsIgnoreCase("default")) yield new RailSign(Kind.SPEED, 0, 0, null, "", Map.of());
                double speed = Track.speed(third);
                yield Double.isNaN(speed) ? null : new RailSign(Kind.SPEED, speed, 0, null, "", Map.of());
            }
            case "dest", "destination" -> third.isEmpty() ? null : new RailSign(Kind.DEST, Double.NaN, 0, null, third, Map.of());
            case "switch", "junction" -> {
                Map<String, BlockFace> routes = new LinkedHashMap<>();
                for (String line : new String[] {third, fourth}) {
                    for (String rule : line.split("[,;]")) {
                        int colon = rule.lastIndexOf(':');
                        if (colon <= 0) continue;
                        BlockFace face = Track.direction(rule.substring(colon + 1));
                        if (face != null) routes.put(rule.substring(0, colon).trim().toLowerCase(Locale.ROOT), face);
                    }
                }
                yield routes.isEmpty() ? null : new RailSign(Kind.SWITCH, Double.NaN, 0, null, "", Map.copyOf(routes));
            }
            case "collect", "depot" -> new RailSign(Kind.COLLECT, Double.NaN, 0, null, "", Map.of());
            case "freight", "cargo" -> {
                String mode = third.isEmpty() ? "both" : third.toLowerCase(Locale.ROOT);
                if (!mode.equals("load") && !mode.equals("unload") && !mode.equals("both")) yield null;
                int seconds = 3;
                if (!fourth.isEmpty()) {
                    try { seconds = Integer.parseInt(fourth.replaceAll("(?i)s$", "")); } catch (NumberFormatException bad) { yield null; }
                }
                yield new RailSign(Kind.FREIGHT, Double.NaN, Math.clamp(seconds, 0, 60), null, mode, Map.of());
            }
            default -> null;
        };
    }

    /** Exit for a cart heading to {@code destination}: its own rule, else {@code *}, else {@code null}. */
    public BlockFace route(String destination) {
        if (destination != null && !destination.isBlank()) {
            BlockFace face = routes.get(destination.trim().toLowerCase(Locale.ROOT));
            if (face != null) return face;
        }
        return routes.get("*");
    }

    private static String clean(String line) { return line == null ? "" : line.strip(); }
}
