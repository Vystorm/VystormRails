package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

class SignCacheTest {
    private RailsPlugin plugin() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        field(plugin, "signCache", new HashMap<>());
        field(plugin, "header", "[rails]");
        doReturn("VystormRails").when(plugin).getName();
        doReturn(true).when(plugin).isEnabled();
        return plugin;
    }

    private void field(Object target, String name, Object value) throws Exception {
        Field field = RailsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Block block(String destination) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getBlockKey()).thenReturn(12345L); // identical XYZ in different worlds
        when(block.getType()).thenReturn(Material.STONE);
        if (destination != null) {
            Sign sign = mock(Sign.class);
            SignSide side = mock(SignSide.class);
            when(block.getState(false)).thenReturn(sign);
            when(sign.getSide(Side.FRONT)).thenReturn(side);
            when(side.line(0)).thenReturn(Component.text("[rails]"));
            when(side.line(1)).thenReturn(Component.text("dest"));
            when(side.line(2)).thenReturn(Component.text(destination));
            when(side.line(3)).thenReturn(Component.empty());
        }
        return block;
    }

    @SuppressWarnings("unchecked")
    private Optional<RailSign> read(RailsPlugin plugin, Block block) throws Exception {
        Method method = RailsPlugin.class.getDeclaredMethod("sign", Block.class);
        method.setAccessible(true);
        return (Optional<RailSign>) method.invoke(plugin, block);
    }

    @Test void identicalCoordinatesKeepEachWorldDestination() throws Exception {
        RailsPlugin plugin = plugin();
        Block first = block("Farm"), second = block("Harbor");
        assertEquals("Farm", read(plugin, first).orElseThrow().text());
        assertEquals("Harbor", read(plugin, second).orElseThrow().text());
        assertEquals("Farm", read(plugin, first).orElseThrow().text());
        verify(first, times(1)).getState(false);
        verify(second, times(1)).getState(false);
    }

    @Test void cachedEmptyBlockDoesNotHideOtherWorldSign() throws Exception {
        RailsPlugin plugin = plugin();
        Block empty = block(null), sign = block("Mine");
        assertTrue(read(plugin, empty).isEmpty());
        assertEquals("Mine", read(plugin, sign).orElseThrow().text());
        assertTrue(read(plugin, empty).isEmpty());
    }

    @Test void placementInvalidatesOnlyItsOwnWorld() throws Exception {
        RailsPlugin plugin = plugin();
        Block first = block("Farm"), second = block("Harbor");
        read(plugin, first);
        read(plugin, second);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getBlock()).thenReturn(first);
        // the handler runs the way Core's stage dispatcher calls it: OBSERVE, skipped for a cancelled placement
        Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting stages = Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting.detached();
        plugin.handleBlockEvents(stages.stages());
        when(event.isCancelled()).thenReturn(true);
        stages.fire(event);
        read(plugin, first);
        verify(first, times(1)).getState(false);
        when(event.isCancelled()).thenReturn(false);
        stages.fire(event);
        read(plugin, first);
        read(plugin, second);
        verify(first, times(2)).getState(false);
        verify(second, times(1)).getState(false);
    }

    @Test void renamedStationInvalidatesTheStaleNameAndRefreshesNearestLookup() throws Exception {
        RailsPlugin plugin = plugin();
        Block signBlock = block(""); World world = signBlock.getWorld();
        SignSide side = ((Sign) signBlock.getState(false)).getSide(Side.FRONT);
        when(side.line(1)).thenReturn(Component.text("station"));
        when(side.line(2)).thenReturn(Component.text("5"));
        when(side.line(3)).thenReturn(Component.text("e Quarry"));
        Block rail = mock(Block.class), support = mock(Block.class), empty = mock(Block.class);
        when(rail.getRelative(org.bukkit.block.BlockFace.DOWN)).thenReturn(support);
        when(support.getRelative(any(org.bukkit.block.BlockFace.class))).thenReturn(empty);
        when(support.getRelative(org.bukkit.block.BlockFace.DOWN)).thenReturn(signBlock);
        when(empty.getWorld()).thenReturn(world);
        when(world.getBlockAt(0, 64, 0)).thenReturn(rail);
        Network network = new Network(); Network.Station old = new Network.Station("Mine", 0, 64, 0);
        network.station(old); doReturn(network).when(plugin).network(world);
        assertFalse(plugin.stationStillThere(world, old));
        assertNull(network.nearest("Mine", 0, 0));
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 0, 64, 0));
        assertEquals("Quarry", plugin.nearestStation(player).name());
    }
}
