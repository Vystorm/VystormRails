package at.esoren.vystormrails;

import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainLinesTest {
    @TempDir Path folder;
    private record Fixture(RailsPlugin plugin, Player player, Minecart cart, World world, TrainLines lines, Map<NamespacedKey,Object> data) {}
    private Fixture fixture() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class)); World world = mock(World.class); Player player = mock(Player.class);
        Minecart cart = mock(Minecart.class); UUID owner = UUID.randomUUID(), worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId); when(player.getUniqueId()).thenReturn(owner); when(player.getWorld()).thenReturn(world);
        when(cart.getWorld()).thenReturn(world); when(cart.getPassengers()).thenReturn(List.of(player));
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.lang()).thenReturn(mock(Lang.class));
        when(plugin.cartOf(player)).thenReturn(cart); when(plugin.lineAssignable(cart, player)).thenReturn(true);
        when(player.hasPermission("vystormrails.train")).thenReturn(true);
        Network network = new Network(); network.station(new Network.Station("Mine", 0,64,0)); network.station(new Network.Station("Port",16,64,0));
        when(plugin.network(world)).thenReturn(network); when(plugin.stationStillThere(eq(world), any())).thenReturn(true);
        Map<NamespacedKey,Object> state = new HashMap<>();
        PersistentDataContainer data = mock(PersistentDataContainer.class, call -> switch(call.getMethod().getName()) {
            case "set" -> { state.put(call.getArgument(0), call.getArgument(2)); yield null; }
            case "get" -> state.get(call.getArgument(0));
            case "getOrDefault" -> state.getOrDefault(call.getArgument(0), call.getArgument(2));
            case "has" -> state.containsKey(call.getArgument(0));
            case "remove" -> { state.remove(call.getArgument(0)); yield null; }
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        when(cart.getPersistentDataContainer()).thenReturn(data);
        TrainLineStore store = new TrainLineStore(folder.resolve("lines.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.put(new TrainLine("metro", owner, worldId, 60, TrainLine.parseStops("Mine@0|Port@30")));
        return new Fixture(plugin,player,cart,world,new TrainLines(plugin),state);
    }
    @Test void actualAssignmentAdvancesOrderedStopsAndRestoresSavedDepartureAfterRestart() throws Exception {
        Fixture f = fixture(); f.lines.command(f.player,"assign metro");
        verify(f.plugin).cartDestination(f.cart,"Mine"); assertTrue(f.lines.bound(f.cart));
        long due = f.lines.arrive(f.cart,"Mine",61000,BlockFace.EAST,.4);
        assertEquals(120000,due); verify(f.plugin).cartDestination(f.cart,"Port");
        new TrainLines(f.plugin).restore(f.cart);
        verify(f.plugin).lineHold(f.cart,120000,BlockFace.EAST,.4);
        assertTrue(f.lines.depart(f.cart));
        assertFalse(f.data.containsKey(new NamespacedKey("vystormrails","line_until")));
        assertEquals(150000,f.lines.arrive(f.cart,"Port",120001,BlockFace.WEST,.4));
    }
    @Test void missingStationStopsInsteadOfDepartingAndStopClearsDurableBinding() throws Exception {
        Fixture f = fixture(); f.lines.command(f.player,"assign metro");
        when(f.plugin.stationStillThere(eq(f.world),any())).thenReturn(false);
        assertFalse(f.lines.depart(f.cart)); assertFalse(f.lines.bound(f.cart));
        verify(f.plugin,atLeastOnce()).cancelLineHold(f.cart);
        when(f.plugin.stationStillThere(eq(f.world),any())).thenReturn(true);
        f.lines.command(f.player,"assign metro"); f.lines.command(f.player,"stop");
        assertFalse(f.lines.bound(f.cart)); assertTrue(f.data.isEmpty());
    }
    @Test void foreignPlayersCannotDeleteLinesOrAssignSomeoneElsesCart() throws Exception {
        Fixture f = fixture(); when(f.player.getUniqueId()).thenReturn(UUID.randomUUID());
        f.lines.command(f.player,"delete metro");
        assertNotNull(new TrainLineStore(folder.resolve("lines.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(f.world.getUID(),"metro"));
        when(f.plugin.lineAssignable(f.cart,f.player)).thenReturn(false); f.lines.command(f.player,"assign metro");
        assertFalse(f.lines.bound(f.cart)); verify(f.plugin,never()).cartDestination(eq(f.cart),anyString());
    }
    @Test void renamedStationInvalidatesTheOldTimetableStop() throws Exception {
        Fixture f = fixture(); f.lines.command(f.player, "assign metro");
        when(f.plugin.stationStillThere(eq(f.world), any())).thenAnswer(invocation -> {
            Network.Station station = invocation.getArgument(1);
            if (station.name().equals("Mine")) f.plugin.network(f.world).station(new Network.Station("Quarry", 0, 64, 0));
            return true;
        });
        assertFalse(f.lines.depart(f.cart));
        assertFalse(f.lines.bound(f.cart));
    }
    @Test void missingDuplicateStationDoesNotInvalidateAnExistingStop() throws Exception {
        Fixture f = fixture(); Network network = f.plugin.network(f.world);
        network.station(new Network.Station("Mine", 32, 64, 0));
        when(f.plugin.stationStillThere(eq(f.world), any())).thenAnswer(invocation -> {
            Network.Station station = invocation.getArgument(1);
            if (station.x() == 0) { network.removeStation(0, 64, 0); return false; }
            return true;
        });
        f.lines.command(f.player, "assign metro");
        assertTrue(f.lines.bound(f.cart));
    }
    @Test void invalidSavedDepartureDoesNotRestoreAnUnsafeHold() throws Exception {
        Fixture f = fixture(); f.lines.command(f.player, "assign metro");
        f.lines.arrive(f.cart, "Mine", 61000, BlockFace.EAST, .4);
        f.data.put(new NamespacedKey("vystormrails", "line_speed"), Double.NaN);
        new TrainLines(f.plugin).restore(f.cart);
        assertFalse(f.lines.bound(f.cart));
        verify(f.plugin, never()).lineHold(any(), anyLong(), any(), anyDouble());
    }
}
