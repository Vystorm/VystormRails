package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Ui;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class RailsPanelTest {
    private static final UUID OWNER = UUID.randomUUID(), WORLD = UUID.randomUUID();
    private final RailsPanel.Actions actions = mock(RailsPanel.Actions.class);

    static Lang lang() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            try (var in = RailsPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        return Lang.create("vystormrails", files);
    }

    static PanelContext context(String code) throws Exception {
        Lang lang = lang();
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(call -> {
            Object[] args = call.getArguments();
            return lang.plain(code, (String) args[0], java.util.Arrays.copyOfRange(args, 1, args.length));
        });
        when(ctx.text(anyString())).thenAnswer(call -> lang.plain(code, call.getArgument(0)));
        return ctx;
    }

    private static RailsPanel.Overview overview(boolean favorites, List<RailsPanel.Station> rows, String selected) {
        List<RailsPanel.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 48; i++) cells.add(new RailsPanel.Cell(i, i % 8 * 8, i / 8 * 8,
                i == 0 ? Material.PLAYER_HEAD : i == 1 ? Material.BELL : Material.RAIL, i == 1 ? "Central" : null, i == 0));
        return new RailsPanel.Overview(OWNER, WORLD, favorites, "Central", 20, 150, rows, selected, 0, 3, "",
                new Ui.Sort("distance", false), favorites ? List.of() : cells, 8, "stations");
    }

    private JsonObject write(RailsPanel.Overview overview, String code) throws Exception {
        return PanelTesting.render(RailsPanel.panel(context(code), overview, actions));
    }

    @Test void givenNetwork_whenRendered_thenMapControlsPagingAndSelectedStationDetailsArePresent() throws Exception {
        var central = new RailsPanel.Station("central", "Central", 10, 64, 20, 22, true);
        var farm = new RailsPanel.Station("farm", "Farm", 30, 70, 40, 50, true);
        JsonObject doc = write(overview(false, List.of(central, farm), "central"), "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("views", "stations", "search", "station-stats", "network-map",
                "cell-47", "zoom", "north", "south", "west", "east", "center", "destination", "other-menu", "clear", "refresh")));
        String text = doc.toString();
        assertTrue(text.contains("20 stations, 150 rails known"), text);
        assertTrue(text.contains("10, 64, 20") && text.contains("22 m away"), text);
        assertTrue(text.contains("Set destination"), text);
        assertFalse(PanelTesting.ids(doc).contains("remove"));
        JsonObject table = PanelTesting.find(doc, "stations");
        assertEquals(3, table.getAsJsonObject("paging").get("pages").getAsInt());
        assertEquals("central", table.get("selected").getAsString());
    }

    @Test void givenMissingFavorite_whenRendered_thenRemovalIsConfirmedAndTravelDisabled() throws Exception {
        var missing = new RailsPanel.Station("missing", "Old Harbor", 0, 0, 0, 0, false);
        JsonObject doc = write(overview(true, List.of(missing), "missing"), "en");
        assertTrue(PanelTesting.find(doc, "destination").get("disabled").getAsBoolean());
        assertTrue(PanelTesting.find(doc, "remove").getAsJsonObject("confirm").toString().contains("Old Harbor"));
        assertTrue(doc.toString().contains("Station is no longer known in this world."));
        assertNull(PanelTesting.find(doc, "network-map"));
        assertNull(PanelTesting.find(doc, "station-stats"));
    }

    @Test void givenEmptyFavorites_whenRendered_thenEmptyGuidanceAndNavigationRemain() throws Exception {
        JsonObject doc = write(overview(true, List.of(), null), "en");
        assertTrue(doc.toString().contains("No favorites. Use /rails favorite NAME"));
        assertNull(PanelTesting.find(doc, "destination"));
        assertNull(PanelTesting.find(doc, "remove"));
        assertNotNull(PanelTesting.find(doc, "other-menu"));
    }

    @Test void givenSevenLanguages_whenRendered_thenPanelLabelsUseEachBundle() throws Exception {
        var row = new RailsPanel.Station("central", "Central", 10, 64, 20, 22, true);
        for (var entry : Map.of("en", "Set destination", "de", "Als Ziel setzen", "fr", "Définir la destination",
                "it", "Imposta destinazione", "pt_br", "Definir destino", "ru", "Выбрать пункт назначения", "zh_cn", "设为目的地").entrySet()) {
            JsonObject doc = write(overview(true, List.of(row), "central"), entry.getKey());
            assertEquals(entry.getValue(), PanelTesting.plain(PanelTesting.find(doc, "destination").get("label")));
            assertFalse(doc.toString().contains("panel-"), doc.toString());
        }
    }

    private static final class Fixture {
        final RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class));
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final PersistentDataContainer data = mock(PersistentDataContainer.class);
        final Network network = new Network();
        final RailsPanel panel;

        Fixture(boolean favorites) throws Exception {
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.lang()).thenReturn(lang());
            when(player.isOnline()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(OWNER);
            when(player.hasPermission("vystormrails.use")).thenReturn(true);
            when(world.getUID()).thenReturn(WORLD);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
            when(player.getPersistentDataContainer()).thenReturn(data);
            when(data.getOrDefault(any(), eq(PersistentDataType.LIST.strings()), anyList())).thenReturn(List.of("Central"));
            when(plugin.network(world)).thenReturn(network);
            when(plugin.stationStillThere(eq(world), any())).thenReturn(true);
            var station = new Network.Station("Central", 10, 64, 20);
            network.station(station);
            when(plugin.favoriteStation(player, "Central")).thenReturn(station);
            panel = new RailsPanel(plugin, favorites);
        }
    }

    private static RailsPanel.Overview withBoard(RailsPanel.Board board) {
        var o = overview(false, List.of(new RailsPanel.Station("central", "Central", 10, 64, 20, 22, true)), "central");
        return new RailsPanel.Overview(o.owner(), o.world(), o.favorites(), o.destination(), o.stationCount(), o.rails(), o.rows(),
                o.selected(), o.page(), o.pages(), o.query(), o.sort(), o.cells(), o.scale(), o.tab(), board);
    }

    @Test void givenStationBoard_whenRendered_thenDeparturesFareAndBuyTicketAreShown() throws Exception {
        var board = new RailsPanel.Board(List.of(new TrainLines.Departure("red", 42, "Farm"), new TrainLines.Departure("blue", 7, "Mine")),
                true, "$5.00", "$50.00", 0);
        JsonObject doc = write(withBoard(board), "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("departures", "ticket", "buy-ticket")), doc.toString());
        String text = doc.toString();
        assertTrue(text.contains("Next departures") && text.contains("red: in 42s → Farm") && text.contains("blue: in 7s → Mine"), text);
        assertTrue(text.contains("$5.00") && text.contains("none ($50.00 for 24 hours)"), text);
        assertTrue(PanelTesting.find(doc, "buy-ticket").getAsJsonObject("confirm").toString().contains("$50.00"), text);
    }

    @Test void givenNoLinesOrNoTickets_whenRendered_thenOnlyTheirPartIsShown() throws Exception {
        JsonObject linesOnly = write(withBoard(new RailsPanel.Board(List.of(), false, null, "-", 0)), "en");
        assertNotNull(PanelTesting.find(linesOnly, "departures"));
        assertTrue(linesOnly.toString().contains("No scheduled lines serve this station."));
        assertNull(PanelTesting.find(linesOnly, "buy-ticket"));
        JsonObject ticketsOnly = write(withBoard(new RailsPanel.Board(null, true, null, "$50.00", 3)), "en");
        assertNull(PanelTesting.find(ticketsOnly, "departures"));
        assertTrue(ticketsOnly.toString().contains("free") && ticketsOnly.toString().contains("valid for 3 more hour(s)"));
        assertNull(PanelTesting.find(write(withBoard(null), "en"), "ticket"));
    }

    @Test void givenSevenLanguages_whenBoardRendered_thenTextsAreTranslatedAndTheButtonFits() throws Exception {
        var board = new RailsPanel.Board(List.of(new TrainLines.Departure("red", 42, "Farm")), true, "5", "50", 0);
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            JsonObject doc = write(withBoard(board), code);
            assertFalse(doc.toString().contains("panel-") || doc.toString().contains("line-departure"), code + doc);
            String label = PanelTesting.plain(PanelTesting.find(doc, "buy-ticket").get("label"));
            assertTrue(label.length() <= 14, code + " " + label);
        }
    }

    @Test void givenTicketsAllowed_whenBuyClicked_thenTheTicketCommandBuys() throws Exception {
        Fixture f = new Fixture(false);
        when(f.player.hasPermission("vystormrails.ticket")).thenReturn(true);
        when(f.plugin.board(f.player, "Central")).thenReturn(new RailsPanel.Board(null, true, null, "50", 0));
        var snapshot = f.panel.overview(f.player);
        assertNotNull(snapshot.board());
        assertTrue(f.panel.act(f.player, snapshot, "ticket", null).success());
        verify(f.plugin).ticket(f.player, true);
        when(f.player.hasPermission("vystormrails.ticket")).thenReturn(false);
        assertFalse(f.panel.act(f.player, snapshot, "ticket", null).success());
        verify(f.plugin, times(1)).ticket(f.player, true);
    }

    @Test void givenLiveFavorite_whenSelectedOrRemoved_thenExistingServicesAreUsed() throws Exception {
        Fixture f = new Fixture(true);
        var snapshot = f.panel.overview(f.player);
        assertTrue(f.panel.act(f.player, snapshot, "destination", "Central").success());
        verify(f.plugin).setDestination(f.player, "Central");
        assertTrue(f.panel.act(f.player, snapshot, "remove", "Central").success());
        verify(f.data).remove(Favorites.key(WORLD));
    }

    @Test void givenStaleOrUnauthorizedSnapshot_whenActing_thenNoDestinationOrFavoritesChange() throws Exception {
        Fixture f = new Fixture(true);
        var snapshot = f.panel.overview(f.player);
        when(f.player.hasPermission("vystormrails.use")).thenReturn(false);
        assertFalse(f.panel.act(f.player, snapshot, "remove", "Central").success());
        when(f.player.hasPermission("vystormrails.use")).thenReturn(true);
        when(f.world.getUID()).thenReturn(UUID.randomUUID());
        assertFalse(f.panel.act(f.player, snapshot, "destination", "Central").success());
        when(f.world.getUID()).thenReturn(WORLD);
        when(f.player.getUniqueId()).thenReturn(UUID.randomUUID());
        assertFalse(f.panel.act(f.player, snapshot, "remove", "Central").success());
        when(f.player.getUniqueId()).thenReturn(OWNER);
        when(f.plugin.favoriteStation(f.player, "Central")).thenReturn(null);
        assertFalse(f.panel.act(f.player, snapshot, "destination", "Central").success());
        when(f.data.getOrDefault(any(), eq(PersistentDataType.LIST.strings()), anyList())).thenReturn(List.of());
        assertFalse(f.panel.act(f.player, snapshot, "remove", "Central").success());
        assertFalse(f.panel.act(f.player, snapshot, "destination", "Unlisted").success());
        assertFalse(f.panel.act(f.player, snapshot, "destination", null).success());
        assertFalse(f.panel.act(f.player, snapshot, "destination", "").success());
        verify(f.plugin, never()).setDestination(any(), any());
        verify(f.data, never()).remove(any());
        verify(f.data, never()).set(any(), any(), any());
    }

    @Test void givenLargeNetwork_whenPagingSortingSearchingAndSelecting_thenStateIsPerPlayerAndClamped() throws Exception {
        Fixture f = new Fixture(false);
        for (int i = 1; i <= 20; i++) f.network.station(new Network.Station("Stop " + i, 100 + i * 10, 64, 0));
        var first = f.panel.overview(f.player);
        assertEquals(9, first.rows().size());
        assertEquals(3, first.pages());
        f.panel.act(f.player, first, "page", 2);
        var last = f.panel.overview(f.player);
        assertEquals(3, last.rows().size());
        String choice = last.rows().getLast().key();
        assertTrue(f.panel.act(f.player, last, "select", choice).success());
        assertEquals(choice, f.panel.overview(f.player).selected());
        f.panel.act(f.player, last, "sort", new Ui.Sort("name", true));
        assertEquals(0, f.panel.overview(f.player).page());
        f.panel.act(f.player, last, "search", "Central");
        var filtered = f.panel.overview(f.player);
        assertEquals(1, filtered.rows().size());
        assertEquals("Central", filtered.rows().getFirst().name());
        when(f.player.getUniqueId()).thenReturn(UUID.randomUUID());
        assertEquals("", f.panel.overview(f.player).query());
        when(f.player.getUniqueId()).thenReturn(OWNER);
        f.panel.forget(OWNER);
        assertEquals("", f.panel.overview(f.player).query());
    }

    @Test void givenMap_whenZoomingPanningAndClearing_thenControlsPreserveTheirBehavior() throws Exception {
        Fixture f = new Fixture(false);
        var before = f.panel.overview(f.player);
        assertEquals(48, before.cells().size());
        f.panel.act(f.player, before, "zoom", "3");
        var zoomed = f.panel.overview(f.player);
        assertEquals(16, zoomed.scale());
        f.panel.act(f.player, zoomed, "east", null);
        assertEquals(zoomed.cells().getFirst().x() + 64, f.panel.overview(f.player).cells().getFirst().x());
        f.panel.act(f.player, zoomed, "center", null);
        assertEquals(zoomed.cells(), f.panel.overview(f.player).cells());
        f.panel.act(f.player, zoomed, "clear", null);
        verify(f.plugin).setDestination(f.player, null);
    }
}
