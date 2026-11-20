package at.esoren.vystormrails;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The feature switches of config.yml (1.21.0 adds the ones marked new). Most are read live, the per-move ones with the
 * rest of the settings in {@code load()}, which Core calls after every change: no switch needs a restart except
 * {@code tutorial.enabled}. A missing key counts as on; switching a feature off never deletes saved data.
 */
final class RailSwitches {
    // new in 1.21.0
    static final String SPEED_BLOCKS = "speed-blocks.enabled";
    static final String SLIDE = "cart-blocks.slide.enabled";
    static final String JUMP = "cart-blocks.jump.enabled";
    static final String BOUNCE = "cart-blocks.bounce.enabled";
    static final String SHIFT = "cart-blocks.shift.enabled";
    static final String SPIN = "cart-blocks.spin.enabled";
    static final String ELEVATOR = "cart-blocks.elevator.enabled";
    static final String FLOAT = "cart-blocks.float.enabled";
    static final String SIGNAL = "cart-blocks.signal.enabled";
    static final String SIGNS = "signs.enabled";
    static final String DESTINATIONS = "destinations.enabled";
    static final String MAP = "map.enabled";
    static final String STATIONS = "stations.enabled";
    static final String FAVORITES = "favorites.enabled";
    static final String LINES = "lines.enabled";
    static final String INFO = "info.enabled";
    static final String TUTORIAL = "tutorial.enabled";
    /** The one language key every switched-off command answers with. */
    static final String OFF = "feature-off";
    /** 1.21.1: the answer for a feature whose own switch is on but which needs another switch ({switch}, {path}). */
    static final String NEEDS = "feature-needs";

    static final List<String> NEW = List.of(SPEED_BLOCKS, SLIDE, JUMP, BOUNCE, SHIFT, SPIN, ELEVATOR, FLOAT, SIGNAL, SIGNS,
            DESTINATIONS, MAP, STATIONS, FAVORITES, LINES, INFO, TUTORIAL);
    /** Switches that existed before 1.21.0 ({@code speedometer} is a top-level boolean Core lists by itself). */
    static final List<String> OLDER = List.of("cart-blocks.steer.enabled", "trains.enabled", "routing.enabled",
            "fares.enabled", "freight.enabled", "speedometer");
    /** Booleans with other names, declared as switches; their keys stay as they are. */
    static final List<String> DECLARED = List.of("speed.slow-before-curves", "collisions.push-mobs",
            "collisions.pass-through-players", "collisions.empty-carts-pick-up-mobs", "pickup.on-exit", "pickup.on-break",
            "arrival.title", "arrival.clear-destination", "routing.player-menu", "cart-blocks.spin.reverse",
            "cart-blocks.float.visual-fire");

    private RailSwitches() {}

    static boolean on(ConfigurationSection config, String key) { return config == null || config.getBoolean(key, true); }

    static List<String> all() {
        List<String> all = new ArrayList<>(NEW);
        all.addAll(OLDER);
        all.addAll(DECLARED);
        return all;
    }

    /** {@code cart-blocks.jump.enabled} -> {@code feature-names.cart-blocks-jump}: the readable name of a switch (1.21.1). */
    static String labelKey(String path) { return "feature-names." + descriptionKey(path).substring("features.".length()); }

    /** {@code cart-blocks.jump.enabled} -> {@code features.cart-blocks-jump}: the language key of a description. */
    static String descriptionKey(String path) {
        String subject = path.endsWith(".enabled") ? path.substring(0, path.length() - ".enabled".length()) : path;
        return "features." + subject.replace('.', '-');
    }
}
