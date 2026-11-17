package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangFilesTest {
    private static YamlConfiguration load(String name) throws Exception {
        try (var in = LangFilesTest.class.getResourceAsStream("/lang/" + name)) {
            assertNotNull(in, name);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test void englishAndGermanHaveTheSameKeysAndPlaceholders() throws Exception {
        YamlConfiguration en = load("en.yml"), de = load("de.yml");
        assertEquals(en.getKeys(true), de.getKeys(true));
        Pattern placeholder = Pattern.compile("\\{[a-z]+}");
        for (String key : en.getKeys(true)) {
            java.util.Set<String> a = new java.util.TreeSet<>(), b = new java.util.TreeSet<>();
            for (Matcher m = placeholder.matcher(en.getString(key)); m.find(); ) a.add(m.group());
            for (Matcher m = placeholder.matcher(de.getString(key)); m.find(); ) b.add(m.group());
            assertEquals(a, b, key);
        }
    }

    @Test void chineseHasTheSameKeysAndPlaceholders() throws Exception {
        YamlConfiguration en = load("en.yml"), zh = load("zh_cn.yml");
        assertEquals(en.getKeys(true), zh.getKeys(true));
        Pattern placeholder = Pattern.compile("\\{[a-z]+}");
        for (String key : en.getKeys(true)) {
            java.util.Set<String> a = new java.util.TreeSet<>(), b = new java.util.TreeSet<>();
            for (Matcher m = placeholder.matcher(en.getString(key)); m.find(); ) a.add(m.group());
            for (Matcher m = placeholder.matcher(zh.getString(key)); m.find(); ) b.add(m.group());
            assertEquals(a, b, key);
            assertFalse(zh.getString(key).contains("<arg"), key);
        }
    }

    @Test void configHasDefaultsForTheNewSections() throws Exception {
        YamlConfiguration config;
        try (var in = LangFilesTest.class.getResourceAsStream("/config.yml")) {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals(18, config.getInt("favorites.max-per-world"));
        assertTrue(config.getBoolean("trains.enabled"));
        assertEquals("*CHAIN", config.getString("trains.coupler"));
        assertTrue(config.getBoolean("routing.enabled"));
        assertEquals(4000, config.getInt("routing.search-limit"));
    }

    @Test void timetableHelpLocalizesArgumentsAndStatesTheRequiredPermissionInEveryLanguage() throws Exception {
        var labels = java.util.Map.of("en", new String[]{"ACTION", "STATION"}, "de", new String[]{"AKTION", "BAHNHOF"},
                "fr", new String[]{"ACTION", "GARE"}, "pt_br", new String[]{"AÇÃO", "ESTAÇÃO"},
                "it", new String[]{"AZIONE", "STAZIONE"}, "ru", new String[]{"ДЕЙСТВИЕ", "СТАНЦИЯ"},
                "zh_cn", new String[]{"操作", "车站"});
        for (var entry : labels.entrySet()) {
            var yaml = load(entry.getKey() + ".yml");
            assertEquals(entry.getValue()[0], yaml.getString("commands.rails.sub.line.args.action"), entry.getKey());
            assertEquals(entry.getValue()[1], yaml.getString("commands.rails.sub.departures.args.station"), entry.getKey());
            assertTrue(yaml.getString("line-cart-denied").contains("vystormrails.train"), entry.getKey());
        }
    }
}
