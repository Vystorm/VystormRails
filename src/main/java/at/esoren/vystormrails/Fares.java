package at.esoren.vystormrails;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Tickets and fares: a station can charge a fare (Vault money) from every rider whose cart leaves it; a day ticket
 * makes all rides free for 24 hours. Fares are set per station name (admins: {@code /rails fare}); tickets are bought
 * with {@code /rails ticket}. Kept in {@code fares.yml}. Pure data, server thread.
 */
final class Fares {
    private final Path file;
    private final Map<String, Double> fares = new TreeMap<>();
    private final Map<UUID, Long> tickets = new HashMap<>();

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    Fares(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        if (!Files.exists(file)) return;
        YamlConfiguration y = YamlCheck.loadData(file, java.util.logging.Logger.getLogger("VystormRails"));
        ConfigurationSection f = y.getConfigurationSection("fares");
        if (f != null) for (String key : f.getKeys(false)) {
            double amount = f.getDouble(key);
            if (Double.isFinite(amount) && amount > 0) fares.put(key, amount);
        }
        ConfigurationSection t = y.getConfigurationSection("tickets");
        if (t != null) for (String key : t.getKeys(false)) try { tickets.put(UUID.fromString(key), t.getLong(key)); } catch (IllegalArgumentException ignored) { }
    }

    static String key(String station) { return station == null ? "" : station.strip().toLowerCase(Locale.ROOT).replace('.', '_'); }

    /** Fare of a station (0 = free). */
    double fare(String station) { return fares.getOrDefault(key(station), 0.0); }

    Map<String, Double> all() { return Collections.unmodifiableMap(fares); }

    void set(String station, double amount) throws IOException {
        if (key(station).isEmpty()) throw new IllegalArgumentException("station");
        if (!Double.isFinite(amount) || amount <= 0) fares.remove(key(station)); else fares.put(key(station), amount);
        save();
    }

    /** End of the player's day ticket, or 0 if none is valid at {@code now}. */
    long ticket(UUID player, long now) { long until = tickets.getOrDefault(player, 0L); return until > now ? until : 0; }

    /** Adds {@code hours} to the ticket (from now, or from its end while still valid); returns the new end. */
    long buy(UUID player, long now, int hours) throws IOException {
        long from = Math.max(now, tickets.getOrDefault(player, 0L));
        long until = from + hours * 3_600_000L;
        Long old = tickets.put(player, until);
        tickets.entrySet().removeIf(e -> e.getValue() <= now);
        try { save(); } catch (IOException failure) { if (old == null) tickets.remove(player); else tickets.put(player, old); throw failure; }
        return until;
    }

    private void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.createSection("fares");
        fares.forEach((station, amount) -> y.set("fares." + station, amount));
        y.createSection("tickets");
        tickets.forEach((player, until) -> y.set("tickets." + player, until));
        Files.createDirectories(file.getParent());
        files.write(file, y.saveToString().getBytes(StandardCharsets.UTF_8));
    }
}
