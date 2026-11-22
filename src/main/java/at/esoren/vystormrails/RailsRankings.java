package at.esoren.vystormrails;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The rankings of Vystorm Rails in the namespace {@code rails} (Core 0.67.0, capability {@code placeholder-rankings}):
 * {@code rails:distance} (blocks ridden in a minecart, shown as m and km) and {@code rails:trips} (rides of at least
 * {@link RideCounts#RIDE_BLOCKS} blocks), both from {@link RideCounts}. The only class that mentions Core's ranking
 * types; it is loaded only after {@link RailsPlaceholders#rankings} checked the capability, so the plugin still loads
 * on an older Core.
 */
final class RailsRankings {
    static final String DISTANCE = "distance", TRIPS = "trips";

    private RailsRankings() {}

    static void apply(Plugin owner, PlaceholderInterface registry, boolean on, RideCounts counts, UnaryOperator<String> ref) {
        if (!on) {
            registry.namespace(RailsPlaceholders.NAMESPACE).ifPresent(ns -> { ns.removeRanking(DISTANCE); ns.removeRanking(TRIPS); });
            return;
        }
        PlaceholderNamespace ns = registry.register(owner, RailsPlaceholders.NAMESPACE);
        ns.ranking(DISTANCE, RankingInfo.of(ref.apply("rankings.distance.label"), ref.apply("rankings.distance.description"))
                        .format(RankingInfo.Format.DISTANCE_BLOCKS),
                n -> counts.topDistance(n).stream().map(e -> new RankingSource.Row(e.getKey(), e.getValue().name, Math.floor(e.getValue().distance))).toList());
        ns.ranking(TRIPS, RankingInfo.of(ref.apply("rankings.trips.label"), ref.apply("rankings.trips.description")),
                n -> counts.topTrips(n).stream().map(e -> new RankingSource.Row(e.getKey(), e.getValue().name, e.getValue().trips)).toList());
    }
}
