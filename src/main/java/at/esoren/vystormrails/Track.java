package at.esoren.vystormrails;

import java.util.Locale;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

/** Pure track geometry: headings, junction shapes, speed units. */
public final class Track {
    /** Blocks per tick to km/h (20 ticks per second, 3.6 km/h per m/s). */
    public static final double KMH_PER_BPT = 72.0;

    private Track() {}

    /** Horizontal heading of a movement; {@code null} when the cart barely moves. */
    public static BlockFace heading(double x, double z) {
        if (Math.abs(x) < 1.0E-3 && Math.abs(z) < 1.0E-3) return null;
        if (Math.abs(x) >= Math.abs(z)) return x > 0 ? BlockFace.EAST : BlockFace.WEST;
        return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /**
     * Rail shape that takes a cart heading {@code heading} out towards {@code exit}: the rail connects the side the
     * cart comes from with the exit side. {@code null} when the exit is the way back or not a horizontal direction.
     */
    public static Rail.Shape junction(BlockFace heading, BlockFace exit) {
        if (!horizontal(heading) || !horizontal(exit) || exit == heading.getOppositeFace()) return null;
        return connecting(heading.getOppositeFace(), exit);
    }

    /** Shape whose two ends are {@code a} and {@code b}; {@code null} for the same side twice. */
    public static Rail.Shape connecting(BlockFace a, BlockFace b) {
        if (a == b) return null;
        boolean north = a == BlockFace.NORTH || b == BlockFace.NORTH, south = a == BlockFace.SOUTH || b == BlockFace.SOUTH;
        boolean east = a == BlockFace.EAST || b == BlockFace.EAST, west = a == BlockFace.WEST || b == BlockFace.WEST;
        if (north && south) return Rail.Shape.NORTH_SOUTH;
        if (east && west) return Rail.Shape.EAST_WEST;
        if (north) return east ? Rail.Shape.NORTH_EAST : Rail.Shape.NORTH_WEST;
        return east ? Rail.Shape.SOUTH_EAST : Rail.Shape.SOUTH_WEST;
    }

    /**
     * Heading of a cart leaving a rail of this shape that it entered heading {@code heading}: curves turn it, straight
     * rails and slopes keep it. {@code null} when a curve does not connect to the side the cart comes from.
     */
    public static BlockFace onwards(Rail.Shape shape, BlockFace heading) {
        if (shape == null || !curve(shape)) return heading;
        String[] ends = shape.name().split("_");
        BlockFace a = BlockFace.valueOf(ends[0]), b = BlockFace.valueOf(ends[1]), from = heading.getOppositeFace();
        return from == a ? b : from == b ? a : null;
    }

    public static boolean curve(Rail.Shape shape) {
        return shape == Rail.Shape.NORTH_EAST || shape == Rail.Shape.NORTH_WEST || shape == Rail.Shape.SOUTH_EAST || shape == Rail.Shape.SOUTH_WEST;
    }

    public static boolean slope(Rail.Shape shape) { return shape != null && shape.name().startsWith("ASCENDING"); }

    public static boolean horizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST;
    }

    /** "n", "north", "e" … to a face; "back" and anything else give {@code null}. */
    public static BlockFace direction(String text) {
        if (text == null) return null;
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "n", "north", "nord" -> BlockFace.NORTH;
            case "s", "south", "süd", "sued" -> BlockFace.SOUTH;
            case "e", "east", "o", "ost" -> BlockFace.EAST;
            case "w", "west" -> BlockFace.WEST;
            default -> null;
        };
    }

    /**
     * Speed in blocks per tick from "0.8", "0.8bpt", "60km/h" or "60kmh"; {@code NaN} when unreadable or not positive.
     */
    public static double speed(String text) {
        if (text == null) return Double.NaN;
        String value = text.trim().toLowerCase(Locale.ROOT).replace(',', '.').replace(" ", "");
        double factor = 1;
        if (value.endsWith("km/h") || value.endsWith("kmh")) {
            factor = 1 / KMH_PER_BPT;
            value = value.substring(0, value.length() - (value.endsWith("km/h") ? 4 : 3));
        } else if (value.endsWith("bpt")) value = value.substring(0, value.length() - 3);
        try {
            double speed = Double.parseDouble(value) * factor;
            return speed > 0 && Double.isFinite(speed) ? speed : Double.NaN;
        } catch (NumberFormatException unreadable) {
            return Double.NaN;
        }
    }

    public static int kmh(double blocksPerTick) { return (int) Math.round(blocksPerTick * KMH_PER_BPT); }
}
