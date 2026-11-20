package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Minecart;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugFixTest {
    @TempDir Path folder;

    static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }

    private RailsPlugin storagePlugin(Network network, UUID world, List<Runnable> writes) throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doReturn(server).when(plugin).getServer();
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> { writes.add(invocation.getArgument(1)); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        field(plugin, "networks", new HashMap<>(Map.of(world, network)));
        field(plugin, "brokenNetworks", new HashSet<UUID>());
        return plugin;
    }

    @Test void failedNetworkWriteIsRetriedWithoutFurtherEdits() throws Exception {
        Network network = new Network(); network.add(1, 2);
        UUID world = UUID.randomUUID();
        RailsPlugin plugin = storagePlugin(network, world, new ArrayList<>());
        Path file = folder.resolve("network").resolve(world + ".bin");
        Files.createDirectories(file);
        call(plugin, "saveNetworks", new Class<?>[]{boolean.class}, false);
        Files.delete(file);
        call(plugin, "saveNetworks", new Class<?>[]{boolean.class}, false);
        assertTrue(Files.isRegularFile(file), "failed snapshots must remain dirty");
        assertTrue(Network.load(Files.readAllBytes(file)).has(1, 2));
    }

    @Test void delayedAutosaveCannotOverwriteShutdownSnapshot() throws Exception {
        Network network = new Network(); network.add(1, 2);
        UUID world = UUID.randomUUID(); List<Runnable> writes = new ArrayList<>();
        RailsPlugin plugin = storagePlugin(network, world, writes);
        call(plugin, "saveNetworks", new Class<?>[]{boolean.class}, true);
        network.add(3, 4);
        call(plugin, "saveNetworks", new Class<?>[]{boolean.class}, false);
        writes.getFirst().run();
        assertTrue(Network.load(Files.readAllBytes(folder.resolve("network").resolve(world + ".bin"))).has(3, 4));
    }

    @Test void shutdownSavesQueuedSnapshotEvenWithoutFurtherEdits() throws Exception {
        Network network = new Network(); network.add(1, 2);
        UUID world = UUID.randomUUID(); List<Runnable> writes = new ArrayList<>();
        RailsPlugin plugin = storagePlugin(network, world, writes);
        call(plugin, "saveNetworks", new Class<?>[]{boolean.class}, true);
        call(plugin, "saveNetworks", new Class<?>[]{boolean.class}, false);
        assertTrue(Files.isRegularFile(folder.resolve("network").resolve(world + ".bin")));
    }

    @Test void malformedNetworkCountsAndTrailingBytesAreRejected() {
        for (int[] values : List.of(new int[]{0x56525431, -1, 0}, new int[]{0x56525431, 0, -1},
                new int[]{0x56525431, 0, 0, 42})) {
            ByteBuffer bytes = ByteBuffer.allocate(values.length * 4);
            for (int value : values) bytes.putInt(value);
            assertThrows(java.io.IOException.class, () -> Network.load(bytes.array()));
        }
    }

    @Test void malformedTimetableRootIsNotAcceptedAsEmpty() throws Exception {
        Path file = folder.resolve("lines.yml");
        for (String value : List.of("broken", "[]")) {
            String contents = "schema: 1\nlines: " + value + "\n";
            Files.writeString(file, contents);
            assertThrows(Exception.class, () -> new TrainLineStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT), value);
            assertEquals(contents, Files.readString(file));
        }
    }

    @Test void restoredCouplingsCannotCloseALoop() {
        Couplings couplings = new Couplings();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        couplings.restore(a, List.of(b)); couplings.restore(b, List.of(a, c));
        assertTrue(couplings.restore(c, List.of(b, a)), "invalid saved link must be repaired");
        assertEquals(1, couplings.neighbours(a).size());
        assertEquals(3, couplings.train(a).size());
    }

    @Test void disabledTrainsDoNotMoveOrMarkCartsAsFollowers() throws Exception {
        Plugin plugin = mock(Plugin.class); when(plugin.getName()).thenReturn("VystormRails");
        when(plugin.namespace()).thenReturn("vystormrails");
        Trains trains = new Trains(plugin, null);
        Couplings couplings = field(trains, "couplings");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); couplings.link(a, b);
        Set<UUID> trailing = field(trains, "trailing"); trailing.add(b);
        YamlConfiguration config = new YamlConfiguration(); config.set("enabled", false); config.set("coupler", "MINECART");
        trains.load(config, 1.5);
        try (var bukkit = mockStatic(Bukkit.class)) {
            trains.tick();
            bukkit.verifyNoInteractions();
        }
        assertFalse(trains.coupled(a)); assertFalse(trains.trailing(b));
        config.set("enabled", true); trains.load(config, 1.5);
        assertTrue(trains.coupled(a), "disabling must preserve saved links");
    }

    @Test void disablingRestoresFloatingAndSlidingCartPhysics() throws Exception {
        CartBlocks blocks = new CartBlocks(new Vystorm.vystorm_core.compat.CompatInterface() {}); Minecart cart = mock(Minecart.class);
        UUID id = UUID.randomUUID(); when(cart.getUniqueId()).thenReturn(id);
        Set<UUID> changed = field(blocks, "changed"), floating = field(blocks, "floating");
        changed.add(id); floating.add(id);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(cart);
            blocks.clear();
        }
        verify(cart).setGravity(true);
        verify(cart).setDerailedVelocityMod(new org.bukkit.util.Vector(.5, .5, .5));
        verify(cart).setFlyingVelocityMod(new org.bukkit.util.Vector(.95, .95, .95));
        verify(cart).setVisualFire(net.kyori.adventure.util.TriState.NOT_SET);
        assertTrue(changed.isEmpty()); assertTrue(floating.isEmpty());
    }

    @Test void railLookupDoesNotReadUnloadedChunks() {
        Block block = mock(Block.class); World world = mock(World.class);
        when(block.getWorld()).thenReturn(world); when(block.getType()).thenReturn(Material.RAIL);
        when(block.getX()).thenReturn(16);
        assertNull(RailsPlugin.railAt(block));
        verify(block, never()).getType();
    }

    @Test void unloadingRestoresPhysicsBeforeEntityIsSaved() throws Exception {
        CartBlocks blocks = new CartBlocks(new Vystorm.vystorm_core.compat.CompatInterface() {}); Minecart cart = mock(Minecart.class);
        UUID id = UUID.randomUUID(); when(cart.getUniqueId()).thenReturn(id);
        Set<UUID> floating = field(blocks, "floating"); floating.add(id);
        var event = mock(org.bukkit.event.world.EntitiesUnloadEvent.class);
        when(event.getEntities()).thenReturn(List.of(cart));
        boolean handled = false;
        for (Method method : CartBlocks.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.bukkit.event.EventHandler.class)
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{org.bukkit.event.world.EntitiesUnloadEvent.class})) {
                method.invoke(blocks, event); handled = true;
            }
        }
        assertTrue(handled, "unloading carts need their temporary physics restored before saving");
        verify(cart).setGravity(true);
        assertTrue(floating.isEmpty());
    }

    @Test void partiallyUnloadedTrainStopsItsLoadedCarts() throws Exception {
        Plugin plugin = mock(Plugin.class); when(plugin.namespace()).thenReturn("vystormrails");
        Trains trains = new Trains(plugin, null); Couplings couplings = field(trains, "couplings");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); couplings.link(a, b);
        Minecart cart = mock(Minecart.class); when(cart.isValid()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(a)).thenReturn(cart);
            trains.tick();
        }
        verify(cart).setVelocity(new org.bukkit.util.Vector());
    }

    @Test void disablingClosesPluginMenusBeforeClickProtectionDisappears() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
        field(plugin, "holds", new HashMap<>()); field(plugin, "signCache", new HashMap<>());
        field(plugin, "routes", new RouteCache(10_000)); field(plugin, "networks", new HashMap<>());
        field(plugin, "cartBlocks", mock(CartBlocks.class));
        Server server = mock(Server.class); doReturn(server).when(plugin).getServer();
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        var view = mock(org.bukkit.inventory.InventoryView.class);
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        when(player.getOpenInventory()).thenReturn(view); when(view.getTopInventory()).thenReturn(inventory);
        try (var configEditor = mockStatic(Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.class)) {
            for (var holder : List.of(mock(NetworkMenu.class), mock(FavoritesMenu.class))) {
                when(inventory.getHolder()).thenReturn(holder);
                plugin.onDisable();
            }
        }
        verify(player, times(2)).closeInventory();
    }
}
