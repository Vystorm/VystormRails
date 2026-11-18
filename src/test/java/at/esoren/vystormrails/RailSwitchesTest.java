package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The 1.21.0 feature switches: off stops the feature, on behaves as before, an old config keeps working. */
class RailSwitchesTest {
    private static YamlConfiguration shipped() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    private static YamlConfiguration with(String key, boolean value) {
        YamlConfiguration config = shipped();
        config.set(key, value);
        return config;
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = RailsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static RailsPlugin plugin(YamlConfiguration config, Lang lang) throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        doReturn(config).when(plugin).getConfig();
        field(plugin, "lang", lang);
        return plugin;
    }

    /**
     * Every cart block set to a name that is no block: CartBlocks warns once per block it reads, so the warnings show
     * which blocks were read at all (the server's block registry is not available in unit tests).
     */
    private static List<String> blocksRead(YamlConfiguration config) {
        for (String block : List.of("jump", "bounce", "shift", "spin", "elevator", "signal")) config.set("cart-blocks." + block + ".block", "NO_" + block.toUpperCase());
        config.set("cart-blocks.ground", null);
        config.set("cart-blocks.ground.NO_SLIDE", 0.9);
        config.set("cart-blocks.float.fluids", List.of("NO_FLOAT"));
        List<String> read = new java.util.ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { read.add(record.getMessage().replace("cart-blocks: unknown block NO_", "").toLowerCase()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        new CartBlocks(new Vystorm.vystorm_core.compat.CompatInterface() {}).load(config.getConfigurationSection("cart-blocks"), logger);
        return read;
    }

    // ---- shipped file, old files, descriptions -------------------------------------------------------------------

    @Test void givenShippedConfig_whenRead_thenEverySwitchIsABooleanAndOn() {
        YamlConfiguration config = shipped();
        for (String key : RailSwitches.all()) assertTrue(config.isBoolean(key), key);
        for (String key : RailSwitches.NEW) assertTrue(config.getBoolean(key), key);
        assertEquals(0.8, config.getDouble("speed.blocks.*COPPER"), "speed list untouched");
    }

    @Test void givenEverySwitch_whenDescribed_thenAllSevenLanguagesHaveTheLine() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String key : RailSwitches.all()) assertFalse(lang.getString(RailSwitches.descriptionKey(key), "").isBlank(), code + " " + key);
            for (String key : RailsPlaceholders.KEYS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder " + key);
            assertFalse(lang.getString(RailSwitches.OFF, "").isBlank(), code + " off message");
        }
    }

    @Test void givenOldConfigWithoutNewKeys_whenRead_thenEverythingIsOnAndTheBlocksAsBefore() {
        YamlConfiguration old = shipped();
        for (String key : RailSwitches.NEW) old.set(key, null);
        for (String key : RailSwitches.NEW) assertTrue(RailSwitches.on(old, key), key);
        assertEquals(blocksRead(shipped()), blocksRead(old));
    }

    // ---- cart blocks ---------------------------------------------------------------------------------------------

    @Test void givenOneCartBlockOff_whenLoaded_thenOnlyThatBlockIsGone() {
        List<String> all = blocksRead(shipped());
        record Case(String key, String line) {}
        for (Case c : List.of(new Case(RailSwitches.SLIDE, "slide"), new Case(RailSwitches.JUMP, "jump"),
                new Case(RailSwitches.BOUNCE, "bounce"), new Case(RailSwitches.SHIFT, "shift"),
                new Case(RailSwitches.SPIN, "spin"), new Case(RailSwitches.ELEVATOR, "elevator"),
                new Case(RailSwitches.FLOAT, "float"), new Case(RailSwitches.SIGNAL, "signal"))) {
            assertTrue(all.contains(c.line()), "on: " + c.line() + " is read " + all);
            List<String> off = blocksRead(with(c.key(), false));
            assertFalse(off.contains(c.line()), "off: " + c.line() + " is not read");
            assertEquals(all.size() - 1, off.size(), "only " + c.line() + " is left out");
        }
    }

    // ---- signs, destinations, lines ------------------------------------------------------------------------------

    @Test void givenSignsDestinationsLinesOff_whenCartsMove_thenNothingActs() throws Exception {
        RailsPlugin plugin = plugin(shipped(), mock(Lang.class));
        var signs = RailsPlugin.class.getDeclaredMethod("signs", Block.class);
        signs.setAccessible(true);
        field(plugin, "signsOff", true);
        assertEquals(List.of(), signs.invoke(plugin, (Block) null), "signs off: no sign is even looked for");
        field(plugin, "destinationsOff", true);
        assertNull(plugin.destination(mock(Minecart.class)), "destinations off: carts ride without one");
        assertNull(plugin.destination(mock(Player.class)));
        field(plugin, "lines", mock(TrainLines.class));
        assertNotNull(plugin.activeLines());
        field(plugin, "linesOff", true);
        assertNull(plugin.activeLines(), "lines off: carts ride like any other cart");
    }

    @Test void givenFeaturesOff_whenTheirCommandsRun_thenOffMessage() throws Exception {
        Player player = mock(Player.class);
        when(player.hasPermission(any(String.class))).thenReturn(true);
        record Case(String key, java.util.function.BiConsumer<RailsPlugin, Player> command) {}
        for (Case c : List.of(new Case(RailSwitches.STATIONS, (p, who) -> p.stations(who, null)),
                new Case(RailSwitches.FAVORITES, (p, who) -> p.favorite(who, "central")),
                new Case(RailSwitches.DESTINATIONS, (p, who) -> p.setDestination(who, "central")),
                new Case(RailSwitches.MAP, RailsPlugin::openNetwork),
                new Case(RailSwitches.FAVORITES, RailsPlugin::openFavorites))) {
            Lang lang = mock(Lang.class);
            RailsPlugin plugin = plugin(with(c.key(), false), lang);
            doReturn(true).when(plugin).isEnabled();
            c.command().accept(plugin, player);
            verify(lang).send(player, RailSwitches.OFF);
        }
        assertFalse(plugin(shipped(), mock(Lang.class)).off(player, RailSwitches.STATIONS), "on: no off message");
    }

    @Test void givenFeaturesSwitched_whenTabCompleting_thenTheSameTreeFollowsTheSwitchAtOnce() throws Exception {
        YamlConfiguration config = with(RailSwitches.LINES, false);
        config.set(RailSwitches.INFO, false);
        var spec = RailsPlugin.commandSpec(plugin(config, mock(Lang.class)));
        List<String> switched = List.of("line", "departures", "info", "blocks");
        for (String name : switched) {
            assertFalse(spec.children().get(name).visibleTo(null), name);
            assertFalse(spec.children().get(name).hidden(), name + " is not hidden for good");
        }
        for (String shown : List.of("dest", "map", "stations", "favorites", "reload")) assertTrue(spec.children().get(shown).visibleTo(null), shown);
        config.set(RailSwitches.LINES, true);
        config.set(RailSwitches.INFO, true);
        config.set(RailSwitches.STATIONS, false);
        for (String name : switched) assertTrue(spec.children().get(name).visibleTo(null), name + " after switching on");
        assertFalse(spec.children().get("stations").visibleTo(null), "stations after switching off");
    }

    @Test void givenReload_whenAListedSwitchChanged_thenCommandListsAreRefreshedAndNothingIsRegisteredAgain() throws Exception {
        RailsPlugin plugin = plugin(shipped(), mock(Lang.class));
        var core = mock(Vystorm.vystorm_core.API.class);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        field(plugin, "core", core);
        List<Boolean> before = RailsPlugin.listing(plugin.getConfig());
        plugin.refreshIfChanged(before);
        verify(commands, never()).refresh();
        doReturn(with(RailSwitches.FAVORITES, false)).when(plugin).getConfig();
        plugin.refreshIfChanged(before);
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    @Test void givenSpeedBlocksAndTutorial_whenRead_thenSwitches() {
        assertFalse(RailSwitches.on(with(RailSwitches.SPEED_BLOCKS, false), RailSwitches.SPEED_BLOCKS));
        assertTrue(shipped().getComments("tutorial").stream().anyMatch(c -> c != null && c.contains("restart")));
    }

    // ---- placeholders --------------------------------------------------------------------------------------------

    private static final class Fake implements RailsPlaceholders.Source {
        boolean destinations = true, fares = true, favorites = true, stations = true;
        final Network network = new Network();
        public boolean destinationsOn() { return destinations; }
        public boolean faresOn() { return fares; }
        public boolean favoritesOn() { return favorites; }
        public boolean stationsOn() { return stations; }
        public String destination(Player player) { return "Central"; }
        public Network network(World world) { return network; }
        public long ticketUntil(UUID player, long now) { return now + 90 * 60_000L; }
        public double fare(String station) { return station.equalsIgnoreCase("central") ? 5 : 0; }
        public int favorites(Player player) { return 2; }
    }

    @Test void givenRegisteredValues_whenResolved_thenPlainTextAndEmptyWhenOff() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        Fake source = new Fake();
        source.network.station(new Network.Station("Central", 30, 64, 40));
        PlaceholderTesting testing = PlaceholderTesting.detached();
        RailsPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormRails"), null, source);
        assertEquals("Central 0 false", testing.resolve(player, "{rails:destination} {rails:speed_kmh} {rails:riding}"));
        assertEquals("1:30 true 2 1 Central 5", testing.resolve(player, "{rails:ticket_remaining} {rails:has_ticket} {rails:favorites_count} {rails:stations_count} {rails:nearest_station} %vystorm_rails_fare_central%"));
        source.destinations = false;
        source.fares = false;
        source.favorites = false;
        source.stations = false;
        assertEquals("[][][][][][]", testing.resolve(player, "[{rails:destination}][{rails:ticket_remaining}][{rails:favorites_count}][{rails:stations_count}][{rails:nearest_station}][{rails:fare:central}]"));
        assertEquals("{rails:riding}", testing.resolveOffThread(player, "{rails:riding}"), "server thread only");
    }
}
