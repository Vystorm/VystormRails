package at.esoren.vystormrails;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Write-before-publish definitions; corrupt files disable this feature without replacing their contents. */
final class TrainLineStore {
    private final Path file;
    private Map<String, TrainLine> lines = new LinkedHashMap<>();
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    TrainLineStore(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) throws Exception {
        this.files = files;
        this.file = file;
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        if (yaml.getInt("schema") != 1) throw new IOException("Unsupported train timetable schema");
        var root = yaml.getConfigurationSection("lines");
        if (root == null) {
            if (yaml.contains("lines")) throw new IOException("Invalid timetable lines section");
            return;
        }
        for (String key : root.getKeys(false)) {
            var row = root.getConfigurationSection(key); if (row == null) throw new IOException("Invalid line");
            List<TrainLine.Stop> stops = new ArrayList<>();
            for (String stop : row.getStringList("stops")) stops.addAll(TrainLine.parseStops(stop));
            TrainLine line = new TrainLine(row.getString("id"), UUID.fromString(row.getString("owner")),
                    UUID.fromString(row.getString("world")), row.getInt("period-seconds"), stops);
            if (lines.put(key(line.world(), line.id()), line) != null || lines.size() > 128) throw new IOException("Duplicate/excess lines");
        }
    }
    static String key(UUID world, String id) { return world + "/" + id; }
    TrainLine get(UUID world, String id) { return lines.get(key(world, id)); }
    List<TrainLine> all(UUID world) { return lines.values().stream().filter(line -> line.world().equals(world)).toList(); }
    void put(TrainLine line) throws IOException {
        Map<String, TrainLine> next = new LinkedHashMap<>(lines); next.put(key(line.world(), line.id()), line);
        if (next.size() > 128 || next.values().stream().filter(value -> value.owner().equals(line.owner())).count() > 16)
            throw new IllegalArgumentException("limit");
        save(next);
    }
    void remove(TrainLine line) throws IOException {
        Map<String, TrainLine> next = new LinkedHashMap<>(lines); next.remove(key(line.world(), line.id())); save(next);
    }
    private void save(Map<String, TrainLine> next) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("schema", 1); int index = 0;
        for (TrainLine line : next.values()) {
            String p = "lines." + index++ + ".";
            yaml.set(p + "id", line.id()); yaml.set(p + "owner", line.owner().toString()); yaml.set(p + "world", line.world().toString());
            yaml.set(p + "period-seconds", line.periodSeconds());
            yaml.set(p + "stops", line.stops().stream().map(stop -> stop.station() + "@" + stop.offsetSeconds()).toList());
        }
        Files.createDirectories(file.getParent()); files.write(file, yaml.saveToString().getBytes(StandardCharsets.UTF_8)); lines = next;
    }
}
