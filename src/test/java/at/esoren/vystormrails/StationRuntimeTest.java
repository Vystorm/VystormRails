package at.esoren.vystormrails;

import static at.esoren.vystormrails.BugFixTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.PassengerTeleport;
import Vystorm.vystorm_core.compat.PlatformCompat;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.*;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class StationRuntimeTest {
    @Test void scheduledArrivalAnnouncesActualWaitAndKeepsArrivalTitle() throws Exception {
        SoundRegistryTestSupport.initialize();
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        Minecart cart = mock(Minecart.class); Player rider = mock(Player.class); World world = mock(World.class);
        Block rail = mock(Block.class); when(rail.getWorld()).thenReturn(world);
        when(rail.getLocation()).thenReturn(new Location(world, 0, 64, 0)); when(rail.getY()).thenReturn(64);
        when(cart.getLocation()).thenReturn(new Location(world, .5, 64, .5));
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID()); when(cart.getVelocity()).thenReturn(new Vector(.4, 0, 0));
        when(cart.getPassengers()).thenReturn(List.of(rider));
        doReturn(new Network()).when(plugin).network(world);
        Lang lang = mock(Lang.class, invocation -> invocation.getMethod().getReturnType() == Component.class
                ? Component.empty() : RETURNS_DEFAULTS.answer(invocation));
        field(plugin, "lang", lang); field(plugin, "holds", new HashMap<>());
        field(plugin, "defaultSpeed", .4); field(plugin, "arrivalTitle", true); field(plugin, "arrivalClear", true);
        TrainLines lines = mock(TrainLines.class); field(plugin, "lines", lines); when(lines.bound(cart)).thenReturn(true);
        AtomicReference<String> destination = new AtomicReference<>("Mine");
        doAnswer(invocation -> destination.get()).when(plugin).destination(cart);
        when(lines.arrive(eq(cart), eq("Mine"), anyLong(), eq(BlockFace.EAST), anyDouble())).thenAnswer(invocation -> {
            destination.set("Port"); return System.currentTimeMillis() + 120_000;
        });
        RailSign sign = RailSign.parse("[rails]", new String[]{"[rails]", "station", "5", "e Mine"});
        try (var teleports = mockStatic(PassengerTeleport.class); var platform = mockStatic(PlatformCompat.class)) {
            call(plugin, "act", new Class<?>[]{Minecart.class, Block.class, RailSign.class, BlockFace.class}, cart, rail, sign, BlockFace.EAST);
        }
        verify(lang).component(rider, "arrival-title", "station", "Mine");
        verify(lang).component(rider, "arrival-subtitle", "station", "Mine");
        verify(lang, never()).component(rider, "arrival-subtitle-cleared", "station", "Mine");
        verify(lang).component(eq(rider), eq("station-arrive"), eq("station"), eq("Mine"), eq("seconds"),
                argThat((String seconds) -> Long.parseLong(seconds) >= 120));
    }

    @Test void boardingCannotReplaceScheduledDestination() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        Minecart cart = mock(Minecart.class); Player rider = mock(Player.class);
        PersistentDataContainer cartData = mock(PersistentDataContainer.class), playerData = mock(PersistentDataContainer.class);
        when(cart.getPersistentDataContainer()).thenReturn(cartData); when(rider.getPersistentDataContainer()).thenReturn(playerData);
        NamespacedKey dest = new NamespacedKey("vystormrails", "dest"); field(plugin, "destKey", dest);
        when(playerData.get(dest, PersistentDataType.STRING)).thenReturn("Private destination");
        TrainLines lines = mock(TrainLines.class); field(plugin, "lines", lines); when(lines.bound(cart)).thenReturn(true);
        plugin.onEnter(new org.bukkit.event.vehicle.VehicleEnterEvent(cart, rider));
        verifyNoInteractions(cartData);
    }

    @Test void signSearchDoesNotReadNeighbouringUnloadedChunks() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        field(plugin, "signCache", new HashMap<>()); field(plugin, "header", "[rails]");
        World world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        Block rail = mock(Block.class), support = mock(Block.class), below = mock(Block.class), side = mock(Block.class);
        when(rail.getRelative(BlockFace.DOWN)).thenReturn(support);
        when(support.getRelative(any(BlockFace.class))).thenReturn(side);
        when(support.getRelative(BlockFace.DOWN)).thenReturn(below);
        when(below.getWorld()).thenReturn(world); when(side.getWorld()).thenReturn(world); when(side.getX()).thenReturn(16);
        call(plugin, "signs", new Class<?>[]{Block.class}, rail);
        verify(side, never()).getBlockData(); verify(side, never()).getState(false);
    }
}
