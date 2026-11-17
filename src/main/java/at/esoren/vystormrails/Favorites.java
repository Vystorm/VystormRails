package at.esoren.vystormrails;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Personal station names, stored per world in the player's persistent data. */
final class Favorites {
    enum Result { ADDED, EXISTS, FULL }

    private Favorites() {}

    static NamespacedKey key(UUID world) { return new NamespacedKey("vystormrails", "favorites/" + world); }

    static List<String> read(Player player, UUID world) {
        return new ArrayList<>(player.getPersistentDataContainer().getOrDefault(key(world), PersistentDataType.LIST.strings(), List.of()));
    }

    static void save(Player player, UUID world, List<String> names) {
        if (names.isEmpty()) player.getPersistentDataContainer().remove(key(world));
        else player.getPersistentDataContainer().set(key(world), PersistentDataType.LIST.strings(), List.copyOf(names));
    }

    static Result add(List<String> names, String name, int limit) {
        if (names.stream().anyMatch(saved -> saved.equalsIgnoreCase(name))) return Result.EXISTS;
        if (names.size() >= limit) return Result.FULL;
        names.add(name);
        return Result.ADDED;
    }
}
