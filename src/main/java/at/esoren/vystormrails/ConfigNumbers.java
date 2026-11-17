package at.esoren.vystormrails;

import org.bukkit.configuration.ConfigurationSection;

/** YAML accepts NaN and infinities, but cart physics requires finite numbers before clamping. */
final class ConfigNumbers {
    private ConfigNumbers() {}

    static double read(ConfigurationSection config, String key, double fallback) {
        double value = config.getDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }
}
