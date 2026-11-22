package at.esoren.vystormrails;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core.lang.Lang;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The {@code rails} placeholders (1.21.0, Vystorm Core 0.65.0): {@code %vystorm_rails_speed_kmh%},
 * {@code {rails:fare:central}} and so on. Plain values; a value whose feature is switched off is empty. They read
 * carts, player data and the station networks, so they answer on the server thread only.
 */
final class RailsPlaceholders {
    static final String NAMESPACE = "rails";
    static final List<String> KEYS = List.of("destination", "speed_kmh", "max_kmh", "destination_distance", "riding",
            "ticket_remaining", "has_ticket", "favorites_count", "stations_count", "nearest_station", "fare");

    /** What the values read, looked up at call time. */
    interface Source {
        boolean destinationsOn();
        boolean faresOn();
        boolean favoritesOn();
        boolean stationsOn();
        /** Destination of the player's cart (or the player's own), null when none. */
        String destination(Player player);
        Network network(World world);
        /** End of the day ticket in epoch millis, 0 when there is none. */
        long ticketUntil(UUID player, long now);
        double fare(String station);
        int favorites(Player player);
    }

    private RailsPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface placeholders, Plugin owner, Lang lang, Source s) {
        PlaceholderNamespace ns = placeholders.register(owner, NAMESPACE);
        add(ns, lang, PlaceholderValue.player("destination", p -> {
            if (!s.destinationsOn()) return "";
            String dest = s.destination(p);
            return dest == null ? "" : dest;
        }));
        add(ns, lang, PlaceholderValue.player("speed_kmh", p -> p.getVehicle() instanceof Minecart cart ? Track.kmh(cart.getVelocity().length()) : 0));
        add(ns, lang, PlaceholderValue.player("max_kmh", p -> p.getVehicle() instanceof Minecart cart ? Track.kmh(cart.getMaxSpeed()) : 0));
        add(ns, lang, PlaceholderValue.player("destination_distance", p -> {
            if (!s.destinationsOn()) return "";
            String dest = s.destination(p);
            if (dest == null) return "";
            Location at = p.getLocation();
            Network.Station target = s.network(p.getWorld()).nearest(dest, at.getBlockX(), at.getBlockZ());
            return target == null ? "" : Network.distance(target, at.getBlockX(), at.getBlockZ());
        }));
        add(ns, lang, PlaceholderValue.player("riding", p -> p.getVehicle() instanceof Minecart));
        add(ns, lang, PlaceholderValue.viewer("ticket_remaining", p -> {
            if (!s.faresOn()) return "";
            long now = System.currentTimeMillis(), until = s.ticketUntil(p.getUniqueId(), now);
            if (until <= now) return "";
            long minutes = (until - now + 59_999) / 60_000;
            return String.format(java.util.Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
        }));
        add(ns, lang, PlaceholderValue.viewer("has_ticket", p -> !s.faresOn() ? "" : s.ticketUntil(p.getUniqueId(), System.currentTimeMillis()) > 0));
        add(ns, lang, PlaceholderValue.player("favorites_count", p -> !s.favoritesOn() ? "" : s.favorites(p)));
        add(ns, lang, PlaceholderValue.player("stations_count", p -> !s.stationsOn() ? "" : s.network(p.getWorld()).stations().size()));
        add(ns, lang, PlaceholderValue.player("nearest_station", p -> {
            if (!s.stationsOn()) return "";
            Network.Station station = s.network(p.getWorld()).nearest(p.getLocation().getBlockX(), p.getLocation().getBlockZ());
            return station == null ? "" : station.name();
        }));
        add(ns, lang, PlaceholderValue.of("fare", context -> {
            String station = context.rest(0);
            if (station == null || station.isBlank()) return null;
            if (!s.faresOn()) return "";
            double fare = s.fare(station);
            return fare == Math.rint(fare) ? Long.toString((long) fare) : Double.toString(fare);
        }).arguments("<station>"));
        return ns;
    }

    /**
     * Declares {@code rails:distance} and {@code rails:trips} while {@code rankings.enabled} is on and removes them when
     * a reload switched it off. {@code coreHasRankings} is {@code CoreApi.has("placeholder-rankings")}: without it
     * nothing is declared and {@link RailsRankings}, the only class that mentions Core's ranking types, is never loaded.
     * A refusal (another plugin owns the namespace, an older Core) is logged and the plugin keeps running.
     */
    static void rankings(PlaceholderInterface placeholders, Plugin owner, boolean coreHasRankings, boolean on, RideCounts counts,
                         Lang lang, java.util.logging.Logger logger) {
        if (!coreHasRankings || placeholders == null || counts == null) return;
        try { RailsRankings.apply(owner, placeholders, on, counts, key -> lang == null ? key : lang.ref(key)); }
        catch (RuntimeException | LinkageError refused) { if (logger != null) logger.warning("Rankings not offered: " + refused); }
    }

    private static void add(PlaceholderNamespace ns, Lang lang, PlaceholderValue value) {
        ns.add(lang == null ? value : value.description(lang.ref("placeholder." + value.key())));
    }
}
