package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml or fares.yml with a YAML error is never saved over, neither at start nor on /rails reload. */
class BrokenFilesTest {
    static final String BROKEN = "speed:\n  cap: 1.5\n  default: 0.4\n broken here\nsign-header: \"[rails]\"\n";

    @TempDir Path folder;
    final List<LogRecord> logged = new ArrayList<>();

    /** YamlConfiguration.loadConfiguration without a server: a parse error leaves an empty config (Bukkit only logs it). */
    static YamlConfiguration bukkitLoad(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try { config.load(file); } catch (Exception ignored) { }
        return config;
    }

    /** The plugin with Bukkit's JavaPlugin config behaviour: reloadConfig() swallows parse errors and leaves an empty config. */
    RailsPlugin plugin() {
        RailsPlugin plugin = mock(RailsPlugin.class, CALLS_REAL_METHODS);
        FileConfiguration[] config = {null};
        File file = folder.resolve("config.yml").toFile();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(logger).when(plugin).getLogger();
        doAnswer(call -> { config[0] = bukkitLoad(file); return null; }).when(plugin).reloadConfig();
        doAnswer(call -> { if (config[0] == null) config[0] = bukkitLoad(file); return config[0]; }).when(plugin).getConfig();
        doAnswer(call -> { config[0].save(file); return null; }).when(plugin).saveConfig();
        return plugin;
    }

    @Test void givenBrokenConfig_whenStartAndReloadReadIt_thenTheFileStaysByteIdenticalAndTheErrorIsLoggedOnce() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        RailsPlugin plugin = plugin();
        for (boolean reload : new boolean[]{false, true}) {
            logged.clear();
            assertNotNull(RailsPlugin.readConfig(plugin, reload));
            assertArrayEquals(before, Files.readAllBytes(file), "config.yml was changed (reload=" + reload + ")");
            assertEquals(1, logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count());
            assertTrue(logged.getFirst().getMessage().contains("line 4, column"), logged.getFirst().getMessage());
        }
        verify(plugin, never()).saveConfig();
    }

    @Test void givenValidConfigWithoutFavoritesLimit_whenRead_thenTheDefaultIsStillAdded() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, "sign-header: \"[rails]\"\n", StandardCharsets.UTF_8);
        assertNull(RailsPlugin.readConfig(plugin(), false));
        assertEquals(18, YamlConfiguration.loadConfiguration(file.toFile()).getInt("favorites.max-per-world"));
    }

    @Test void givenBrokenConfig_whenReloaded_thenLoadReportsTheProblemAndKeepsTheLastSettings() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        RailsPlugin plugin = plugin();
        set(plugin, "configLoaded", true);
        set(plugin, "favoritesLimit", 7);
        String problem = plugin.load();
        assertNotNull(problem);
        assertTrue(problem.startsWith("line 4, column "), problem);
        assertEquals(7, get(plugin, "favoritesLimit"), "the last good settings stay active");
        verify(plugin, never()).reloadConfig();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml")).getString("config-error", "").contains("{problem}"), code);
    }

    @Test void givenBrokenFares_whenAFareIsSaved_thenTheBrokenFileWasCopiedAsideFirst() throws Exception {
        Path file = folder.resolve("fares.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        Fares fares = new Fares(file, AtomicFilesInterface.DEFAULT);
        assertArrayEquals(before, Files.readAllBytes(file), "loading alone changes nothing");
        fares.set("central", 5);
        try (Stream<Path> files = Files.list(folder)) {
            Path copy = files.filter(p -> p.getFileName().toString().startsWith("fares.yml.broken-")).findFirst().orElseThrow(() -> new AssertionError("no copy"));
            assertArrayEquals(before, Files.readAllBytes(copy));
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = RailsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = RailsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
