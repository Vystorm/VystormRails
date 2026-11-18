package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /rails through Core's command tree with the real language files: names, completion and help per language. */
class RailsCommandsTest {
    private final List<String> chat = new ArrayList<>();

    @Test void givenMenuCommands_whenExecuted_thenUsePanelAwareOpeners() throws Exception {
        RailsPlugin rails = TestCore.attach(mock(RailsPlugin.class));
        CommandRegistration registration = registration(rails);
        Player player = player(Locale.US, "vystormrails.use");
        registration.execute(player, "rails", new String[0]);
        registration.execute(player, "rails", new String[]{"map"});
        registration.execute(player, "rails", new String[]{"favorites"});
        verify(rails, times(2)).openNetwork(player);
        verify(rails).openFavorites(player);
    }

    private static String read(String name) throws Exception {
        try (InputStream in = RailsCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CommandRegistration registration() throws Exception {
        return registration(null);
    }

    private CommandRegistration registration(RailsPlugin rails) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormRails");
        Lang lang = Lang.create("VystormRails", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        return CommandTesting.detached(plugin, lang, RailsPlugin.commandSpec(rails));
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration();
        String[] none = {""};
        assertEquals(List.of("dest", "map", "line", "departures", "stations", "favorite", "favorites", "info", "blocks", "help"), r.suggest(player(Locale.US, "vystormrails.use"), "rails", none));
        assertEquals(List.of("ziel", "karte", "linie", "abfahrten", "bahnhoefe", "favorit", "favoriten", "info", "bloecke", "abkoppeln", "hilfe"),
                r.suggest(player(Locale.GERMANY, "vystormrails.use", "vystormrails.train"), "rails", none));
        assertEquals(List.of("目的地", "地图", "线路", "发车", "车站", "收藏", "收藏夹", "信息", "方块", "脱钩", "重载", "帮助"),
                r.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormrails.use", "vystormrails.train", "vystormrails.admin"), "rails", none));
        assertEquals(List.of(), r.suggest(player(Locale.US), "rails", none), "without vystormrails.use nothing");
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration().execute(player(Locale.GERMANY, "vystormrails.use"), "schienen", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/schienen ziel [BAHNHOF|löschen...] – Ziel anzeigen oder setzen"), all);
        assertTrue(all.contains("/schienen karte – Streckenkarte öffnen"), all);
        assertFalse(all.contains("neuladen"), all);
    }

    @Test
    void givenRootAliases_whenRead_thenGermanAndChinese() throws Exception {
        CommandRegistration r = registration();
        assertEquals(List.of("vrails", "cart"), r.fixedAliases());
        Lang lang = r.lang();
        assertEquals("[schienen]", "[" + lang.raw("de", "commands.rails.aliases") + "]");
        assertEquals("铁轨", lang.raw("zh_cn", "commands.rails.aliases"));
    }

    @Test
    void givenConsole_whenPlayerOnlySub_thenRefused() throws Exception {
        var console = mock(org.bukkit.command.ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        registration().execute(console, "rails", new String[]{"karte"});
        assertEquals("Only players can do that.", chat.get(0));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() throws Exception {
        assertEquals(List.of(), registration().conflicts());
    }

    @Test void stationSearchDispatchesLocalizedNamesAndGreedyArgument() throws Exception {
        RailsPlugin rails = TestCore.attach(mock(RailsPlugin.class));
        CommandRegistration registration = registration(rails);
        Player player = player(Locale.US, "vystormrails.use");
        for (String name : List.of("stations", "bahnhoefe", "车站"))
            registration.execute(player, "rails", new String[]{name, "North", "Farm"});
        verify(rails, times(3)).stations(player, "North Farm");
        registration.execute(player, "rails", new String[]{"stations"});
        verify(rails).stations(player, null);
    }

    @Test void stationSearchRequiresPlayerAndUsePermission() throws Exception {
        RailsPlugin rails = TestCore.attach(mock(RailsPlugin.class));
        CommandRegistration registration = registration(rails);
        clearInvocations(rails); // building the tree asks which subcommands are switched off (1.21.0)
        registration.execute(player(Locale.US), "rails", new String[]{"stations"});
        var console = mock(org.bukkit.command.ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        registration.execute(console, "rails", new String[]{"stations"});
        verifyNoInteractions(rails);
    }

    @Test void stationDirectoryLimitsOutputAndUsesThePlayersWorld() throws Exception {
        RailsPlugin rails = TestCore.attach(mock(RailsPlugin.class));
        var registration = registration(rails);
        var field = RailsPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        field.set(rails, registration.lang());
        Player player = player(Locale.US, "vystormrails.use");
        var world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 0, 64, 0));
        Network network = new Network();
        for (int i = 12; i > 0; i--) network.station(new Network.Station("Station " + i, i * 10, 64, 0));
        when(rails.network(world)).thenReturn(network);
        doCallRealMethod().when(rails).stations(any(Player.class), nullable(String.class));
        registration.execute(player, "rails", new String[]{"stations"});
        assertEquals(12, chat.size());
        assertTrue(chat.getFirst().contains("10 of 12"), chat.toString());
        assertTrue(chat.get(1).contains("Station 1"), chat.toString());
        assertTrue(chat.get(10).contains("Station 10"), chat.toString());
        assertTrue(chat.get(1).contains("10 m, XYZ: 10, 64, 0"), chat.toString());
        chat.clear();
        registration.execute(player, "rails", new String[]{"stations", "missing"});
        assertEquals(List.of("No known named stations match in this world."), chat);
    }
    @Test void favoritesDispatchLocalizedNamesAndRequirePermission() throws Exception {
        RailsPlugin rails = TestCore.attach(mock(RailsPlugin.class));
        CommandRegistration registration = registration(rails);
        Player player = player(Locale.US, "vystormrails.use");
        for (String name : List.of("favorite", "favorit", "收藏"))
            registration.execute(player, "rails", new String[]{name, "North", "Farm"});
        verify(rails, times(3)).favorite(player, "North Farm");
        clearInvocations(rails);
        registration.execute(player(Locale.US), "rails", new String[]{"favorite", "Farm"});
        registration.execute(player, "rails", new String[]{"favorite"});
        var console = mock(org.bukkit.command.ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        registration.execute(console, "rails", new String[]{"favorite", "Farm"});
        registration.execute(console, "rails", new String[]{"favorites"});
        verifyNoInteractions(rails);
    }

    @Test void destinationCommandPreservesLongStationNames() throws Exception {
        RailsPlugin rails = TestCore.attach(mock(RailsPlugin.class));
        Player player = player(Locale.US, "vystormrails.use");
        String name = "Northern Industrial District Central Station";
        registration(rails).execute(player, "rails", new String[]{"dest", name});
        verify(rails).setDestination(player, name);
    }

    @Test
    void givenLineCommand_whenCompleting_thenActionsThenLineIds() throws Exception {
        assertEquals(List.of("list", "show", "assign", "stop", "delete", "create"), TrainLines.suggest(0, null, List.of()));
        assertEquals(List.of("a1", "b2"), TrainLines.suggest(1, "Show", List.of("a1", "b2")));
        assertEquals(List.of(), TrainLines.suggest(1, "create", List.of("a1")));
        assertEquals(List.of(), TrainLines.suggest(2, "show", List.of("a1")));
        CommandRegistration r = registration(TestCore.attach(mock(RailsPlugin.class)));
        Player player = player(Locale.US, "vystormrails.use");
        assertEquals(List.of("stop", "show"), r.suggest(player, "rails", new String[]{"line", "s"}).stream().sorted(java.util.Comparator.reverseOrder()).toList());
    }
}
